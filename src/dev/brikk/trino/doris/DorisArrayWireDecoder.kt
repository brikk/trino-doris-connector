/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.trino.doris

import io.trino.plugin.jdbc.StandardColumnMappings.toTrinoTimestamp
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException
import io.trino.spi.block.Block
import io.trino.spi.block.BlockBuilder
import io.trino.spi.type.Int128
import io.trino.spi.type.TypeUtils.writeNativeValue
import java.lang.Float.floatToRawIntBits
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

/**
 * Strict recursive-descent decoder for the PROVEN Doris 4.1.3 ARRAY `getString()` wire grammar
 * (`REPORT-array-wire-decoder-spike.md` §2; the checked-in spike is evidence, this is the
 * production implementation):
 *
 * ```
 * array        = "[" [ element *( ", " element ) ] "]"     ; separator is comma + ONE space
 * element      = "null" | bare-scalar | quoted-scalar | array
 * bare-scalar  = int | largeint | decimal | float | boolean("1"/"0")
 * quoted-scalar= '"' <raw bytes, NO escaping> '"'          ; DATE / DATETIME / IPV4 / IPV6 only
 * ```
 *
 * Distinctions preserved exactly (spike F1): `[]` = empty array, bare `null` = NULL element,
 * SQL NULL array = JDBC `getString()` null (handled by the caller's isNull check, never here).
 *
 * Because quoted content carries NO escaping (spike F4 — the reason string-family elements are
 * DENIED), the closing quote of a quoted element is the first `"` followed by `, ` or `]`;
 * an interior `"` in any allowlisted quoted kind (DATE/DATETIME/IP — alphabets exclude `"`)
 * is an alphabet violation and fails loud. Everything outside the grammar fails loud with the
 * wire offset; there is NO permissive fallback (spike F6: 12/12 malformed samples throw).
 *
 * Output values are Trino native carriers ready for [elementsBlock]:
 * Long (integers / epoch-day / epoch-micros / float bits / short-decimal unscaled),
 * Int128 (LARGEINT and long DECIMAL), Boolean, Double, Slice (IPADDRESS 16 bytes),
 * List (nested arrays), null (NULL element).
 */
internal object DorisArrayWireDecoder {
    /** Doris DATETIME wire text: `uuuu-MM-dd HH:mm:ss` + optional fraction of the declared scale (1..6 digits). */
    private val DATETIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, true)
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)

    fun decode(wire: String, element: DorisArrayElement): List<Any?> {
        val parser = Parser(wire)
        val values = parser.parseArray(element)
        parser.expectEnd()
        return values
    }

    /** Builds the Trino elements Block for an array value (PostgreSqlClient Block-construction pattern). */
    fun elementsBlock(element: DorisArrayElement, values: List<Any?>): Block {
        val builder: BlockBuilder = element.trinoType.createBlockBuilder(null, values.size)
        for (value in values) {
            when {
                value == null -> builder.appendNull()
                element is DorisArrayElement.NestedArrayElement -> {
                    check(value is List<*>) { "nested array element decoded to ${value.javaClass.name}, expected List" }
                    element.arrayType.writeObject(builder, elementsBlock(element.element, value))
                }
                else -> writeNativeValue(element.trinoType, builder, value)
            }
        }
        return builder.build()
    }

    private class Parser(private val wire: String) {
        private val length = wire.length
        private var position = 0

        fun fail(message: String, at: Int): Nothing {
            throw TrinoException(
                GENERIC_INTERNAL_ERROR,
                "Malformed Doris ARRAY wire text: $message (at wire offset $at) in '${truncate(wire)}'",
            )
        }

        fun expectEnd() {
            if (position != length) {
                fail("trailing garbage after array: '${truncate(wire.substring(position))}'", position)
            }
        }

        fun parseArray(element: DorisArrayElement): List<Any?> {
            if (position >= length || wire[position] != '[') {
                fail("expected '[' to open array", position)
            }
            position++
            val values = ArrayList<Any?>()
            if (position < length && wire[position] == ']') {
                position++
                return values
            }
            while (true) {
                values.add(parseElement(element))
                if (position >= length) {
                    fail("unterminated array (end of text before ']')", position)
                }
                when (val c = wire[position]) {
                    ']' -> {
                        position++
                        return values
                    }
                    ',' -> {
                        // separator MUST be exactly comma + one space (byte-verified grammar)
                        if (position + 1 >= length || wire[position + 1] != ' ') {
                            fail("separator ',' not followed by a space", position)
                        }
                        position += 2
                    }
                    else -> fail("expected ', ' or ']' after element, saw '$c'", position)
                }
            }
        }

        private fun parseElement(element: DorisArrayElement): Any? {
            if (wire.regionMatches(position, NULL_TOKEN, 0, NULL_TOKEN.length)) {
                // A bare null must be bounded by the separator or ']' (spike guard).
                val after = position + NULL_TOKEN.length
                if (after == length || wire[after] == ',' || wire[after] == ']') {
                    position = after
                    return null
                }
                fail("bare token starting with 'null' is not a valid element", position)
            }
            return when (element) {
                is DorisArrayElement.NestedArrayElement -> parseArray(element.element)
                is DorisArrayElement.DateElement,
                is DorisArrayElement.TimestampElement,
                is DorisArrayElement.IpAddressElement,
                -> convertQuoted(element, parseQuoted(element), position)
                else -> parseBare(element)
            }
        }

        /** Bare (unquoted) token: everything up to the next ',' or ']'. */
        private fun parseBare(element: DorisArrayElement): Any {
            val start = position
            while (position < length && wire[position] != ',' && wire[position] != ']') {
                position++
            }
            val token = wire.substring(start, position)
            if (token.isEmpty()) {
                fail("empty bare token", start)
            }
            return convertBare(element, token, start)
        }

        private fun convertBare(element: DorisArrayElement, token: String, at: Int): Any {
            try {
                return when (element) {
                    is DorisArrayElement.BooleanElement -> decodeBoolean(token, at)
                    is DorisArrayElement.TinyintElement -> rangeCheckedLong(token, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong(), "TINYINT", at)
                    is DorisArrayElement.SmallintElement -> rangeCheckedLong(token, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong(), "SMALLINT", at)
                    is DorisArrayElement.IntegerElement -> rangeCheckedLong(token, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "INT", at)
                    is DorisArrayElement.BigintElement -> token.toLong()
                    // exact 128-bit via BigInteger; out-of-DECIMAL(38,0) fails loud, never clamps
                    is DorisArrayElement.LargeintElement -> DorisTypeMapping.parseLargeint(token)
                    is DorisArrayElement.DecimalElement -> decodeDecimal(element, token, at)
                    is DorisArrayElement.RealElement -> floatToRawIntBits(strictFloat(token)).toLong()
                    is DorisArrayElement.DoubleElement -> strictDouble(token)
                    else -> fail("element kind ${element.javaClass.simpleName} is not a bare token", at)
                }
            } catch (e: NumberFormatException) {
                fail("malformed ${element.javaClass.simpleName} token '$token': ${e.message}", at)
            }
        }

        /** The wire renders booleans as 1/0, never true/false (spike §2). */
        private fun decodeBoolean(token: String, at: Int): Boolean = when (token) {
            "1" -> true
            "0" -> false
            else -> fail("BOOLEAN element is not 1/0: '$token'", at)
        }

        private fun rangeCheckedLong(token: String, min: Long, max: Long, kind: String, at: Int): Long {
            val value = token.toLong()
            if (value < min || value > max) {
                fail("$kind element out of range: $token", at)
            }
            return value
        }

        /**
         * BigDecimal decode — never through double, trailing zeros/scale preserved (spike F2).
         * Rescale to the declared scale is exact-only; more fractional digits than declared
         * (impossible per grammar, "fraction width = declared scale") fails loud.
         */
        private fun decodeDecimal(element: DorisArrayElement.DecimalElement, token: String, at: Int): Any {
            val decimalType = element.decimalType
            val value = try {
                BigDecimal(token).setScale(decimalType.scale, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                fail("DECIMAL element '$token' does not fit scale ${decimalType.scale}: ${e.message}", at)
            }
            if (value.precision() > decimalType.precision) {
                fail("DECIMAL element '$token' exceeds precision ${decimalType.precision}", at)
            }
            return if (decimalType.isShort) {
                value.unscaledValue().longValueExact()
            } else {
                Int128.valueOf(value.unscaledValue())
            }
        }

        /**
         * Surfacing Infinity/-Infinity/NaN faithfully is MANDATED for approximate types
         * (ledger §A F3 caveat): Doris renders DOUBLE max as a 16-sig-digit form that reparses
         * to Infinity — never a silently-wrong finite value, never a decode error.
         */
        private fun strictFloat(token: String): Float = when (token) {
            "Infinity" -> Float.POSITIVE_INFINITY
            "-Infinity" -> Float.NEGATIVE_INFINITY
            "NaN" -> Float.NaN
            else -> token.toFloat()
        }

        private fun strictDouble(token: String): Double = when (token) {
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            "NaN" -> Double.NaN
            else -> token.toDouble()
        }

        /**
         * Quoted element. Because the content carries NO escaping, the closing `"` is the
         * first `"` followed by `, ` / `]` / end-of-text. For the allowlisted quoted kinds
         * (DATE/DATETIME/IP) whose value alphabets exclude `"`, this rule is EXACT; an
         * interior `"` is an alphabet violation and fails loud (spike §4).
         */
        private fun parseQuoted(element: DorisArrayElement): String {
            if (position >= length || wire[position] != '"') {
                fail("expected '\"' to open a quoted ${element.javaClass.simpleName} element", position)
            }
            val contentStart = position + 1
            var scan = contentStart
            while (true) {
                if (scan >= length) {
                    fail("unterminated quoted element (no closing '\"')", position)
                }
                if (wire[scan] == '"') {
                    val after = scan + 1
                    val structural = after >= length ||
                        wire[after] == ']' ||
                        (wire[after] == ',' && after + 1 < length && wire[after + 1] == ' ')
                    if (structural) {
                        break
                    }
                    fail("interior '\"' in a ${element.javaClass.simpleName} element (alphabet violation)", scan)
                }
                scan++
            }
            val raw = wire.substring(contentStart, scan)
            position = scan + 1
            return raw
        }

        private fun convertQuoted(element: DorisArrayElement, raw: String, at: Int): Any {
            return when (element) {
                is DorisArrayElement.DateElement -> parseDate(raw, at)
                is DorisArrayElement.TimestampElement -> parseTimestamp(element, raw, at)
                is DorisArrayElement.IpAddressElement -> DorisTypeMapping.ipAddressSlice(raw)
                else -> fail("element kind ${element.javaClass.simpleName} is not a quoted element", at)
            }
        }

        private fun parseDate(raw: String, at: Int): Long {
            try {
                return LocalDate.parse(raw, DATE_FORMATTER).toEpochDay()
            } catch (e: DateTimeParseException) {
                fail("malformed DATE element '$raw': ${e.message}", at)
            }
        }

        private fun parseTimestamp(element: DorisArrayElement.TimestampElement, raw: String, at: Int): Long {
            val localDateTime = try {
                LocalDateTime.parse(raw, DATETIME_FORMATTER)
            } catch (e: DateTimeParseException) {
                fail("malformed DATETIME element '$raw': ${e.message}", at)
            }
            try {
                return toTrinoTimestamp(element.timestampType, localDateTime)
            } catch (e: com.google.common.base.VerifyException) {
                // wire fraction finer than the declared precision — outside the grammar
                fail("DATETIME element '$raw' does not fit ${element.timestampType}: ${e.message}", at)
            }
        }

        private fun truncate(text: String): String =
            if (text.length <= MAX_ERROR_TEXT) text else text.substring(0, MAX_ERROR_TEXT) + "..."
    }

    private const val NULL_TOKEN = "null"
    private const val MAX_ERROR_TEXT = 200
}
