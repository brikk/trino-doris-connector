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

import dev.brikk.trino.doris.DorisArrayElement.BigintElement
import dev.brikk.trino.doris.DorisArrayElement.BooleanElement
import dev.brikk.trino.doris.DorisArrayElement.DateElement
import dev.brikk.trino.doris.DorisArrayElement.DecimalElement
import dev.brikk.trino.doris.DorisArrayElement.DoubleElement
import dev.brikk.trino.doris.DorisArrayElement.IntegerElement
import dev.brikk.trino.doris.DorisArrayElement.IpAddressElement
import dev.brikk.trino.doris.DorisArrayElement.LargeintElement
import dev.brikk.trino.doris.DorisArrayElement.NestedArrayElement
import dev.brikk.trino.doris.DorisArrayElement.RealElement
import dev.brikk.trino.doris.DorisArrayElement.SmallintElement
import dev.brikk.trino.doris.DorisArrayElement.TimestampElement
import dev.brikk.trino.doris.DorisArrayElement.TinyintElement
import io.trino.spi.TrinoException
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.Int128
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.Float.floatToRawIntBits
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Decoder unit tests over the spike report's fixture classes — wire texts are the VERBATIM
 * `getString()` values from `REPORT-array-wire-decoder-spike.md` §3, and the malformed
 * battery is §5 (12/12 must fail loud with wire-offset info). No cluster needed.
 */
class TestDorisArrayWireDecoder {
    private val ipAddressType = TESTING_TYPE_MANAGER.getType(io.trino.spi.type.TypeDescriptor("ipaddress"))

    // --- F1: empty vs NULL element vs (SQL NULL handled by caller) ---

    @Test
    fun testIntArraysWithNullsAndEmpty() {
        assertThat(DorisArrayWireDecoder.decode("[1, null, 3]", IntegerElement)).containsExactly(1L, null, 3L)
        assertThat(DorisArrayWireDecoder.decode("[]", IntegerElement)).isEmpty()
        assertThat(DorisArrayWireDecoder.decode("[null]", IntegerElement)).containsExactly(null)
        assertThat(DorisArrayWireDecoder.decode("[-2147483648, 2147483647]", IntegerElement))
            .containsExactly(-2147483648L, 2147483647L)
    }

    @Test
    fun testIntegerKindsAndRanges() {
        assertThat(DorisArrayWireDecoder.decode("[-128, 127]", TinyintElement)).containsExactly(-128L, 127L)
        assertThat(DorisArrayWireDecoder.decode("[-32768, 32767]", SmallintElement)).containsExactly(-32768L, 32767L)
        assertThat(DorisArrayWireDecoder.decode("[-9223372036854775808, 9223372036854775807]", BigintElement))
            .containsExactly(Long.MIN_VALUE, Long.MAX_VALUE)
    }

    // --- LARGEINT: exact 128-bit, fail loud out of DECIMAL(38,0) (spike §7.4) ---

    @Test
    fun testLargeintExactAndFailLoudOutOfDecimal38() {
        val inRange = "99999999999999999999999999999999999999"
        assertThat(DorisArrayWireDecoder.decode("[$inRange, -$inRange]", LargeintElement))
            .containsExactly(Int128.valueOf(BigInteger(inRange)), Int128.valueOf(BigInteger("-$inRange")))
        // The ±(2^127−1) extremes round-trip through BigInteger but exceed DECIMAL(38,0).
        assertThatThrownBy { DorisArrayWireDecoder.decode("[170141183460469231731687303715884105727]", LargeintElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("out of DECIMAL(38, 0) range")
        assertThatThrownBy { DorisArrayWireDecoder.decode("[-170141183460469231731687303715884105727]", LargeintElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("out of DECIMAL(38, 0) range")
    }

    // --- DECIMAL: trailing zeros / scale preserved, no double round-trip (spike F2) ---

    @Test
    fun testDecimalScalePreserved() {
        // "[1.10]" scale 2 -> unscaled 110 (short decimal)
        assertThat(DorisArrayWireDecoder.decode("[1.10]", DecimalElement(createDecimalType(9, 2))))
            .containsExactly(110L)
        // "[1.0000000000]" scale 10 long decimal -> unscaled 10^10 as Int128
        assertThat(DorisArrayWireDecoder.decode("[1.0000000000]", DecimalElement(createDecimalType(38, 10))))
            .containsExactly(Int128.valueOf(10_000_000_000L))
        assertThat(DorisArrayWireDecoder.decode("[-9999999.99]", DecimalElement(createDecimalType(9, 2))))
            .containsExactly(-999999999L)
        // finer scale than declared is outside the grammar -> fail loud
        assertThatThrownBy { DorisArrayWireDecoder.decode("[1.123]", DecimalElement(createDecimalType(9, 2))) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("does not fit scale")
    }

    // --- BOOLEAN: bare 1/0, never true/false (spike §2) ---

    @Test
    fun testBooleanElements() {
        assertThat(DorisArrayWireDecoder.decode("[null, 1, 0]", BooleanElement)).containsExactly(null, true, false)
        assertThatThrownBy { DorisArrayWireDecoder.decode("[2, true]", BooleanElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("BOOLEAN element is not 1/0")
        assertThatThrownBy { DorisArrayWireDecoder.decode("[true]", BooleanElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("BOOLEAN element is not 1/0")
    }

    // --- FLOAT/DOUBLE: value-exact; F3 boundary surfaces Infinity faithfully ---

    @Test
    fun testFloatAndDoubleElements() {
        assertThat(DorisArrayWireDecoder.decode("[3.402823e+38, -3.402823e+38]", RealElement))
            .containsExactly(floatToRawIntBits(3.402823e38f).toLong(), floatToRawIntBits(-3.402823e38f).toLong())
        assertThat(DorisArrayWireDecoder.decode("[NaN]", RealElement))
            .containsExactly(floatToRawIntBits(Float.NaN).toLong())
        // F3: Doris renders DOUBLE max as 16 sig digits, which reparses to Infinity — surfaced
        // faithfully, never a silently-wrong finite value, never a decode error.
        assertThat(DorisArrayWireDecoder.decode("[1.797693134862316e+308]", DoubleElement))
            .containsExactly(Double.POSITIVE_INFINITY)
        assertThat(DorisArrayWireDecoder.decode("[-1.797693134862316e+308]", DoubleElement))
            .containsExactly(Double.NEGATIVE_INFINITY)
        assertThat(DorisArrayWireDecoder.decode("[2.718281828459045, Infinity, -Infinity, NaN]", DoubleElement))
            .containsExactly(2.718281828459045, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)
    }

    // --- DATE / DATETIME: quoted, edges, microseconds ---

    @Test
    fun testDateElements() {
        assertThat(DorisArrayWireDecoder.decode("""["0000-01-01", "9999-12-31"]""", DateElement))
            .containsExactly(LocalDate.of(0, 1, 1).toEpochDay(), LocalDate.of(9999, 12, 31).toEpochDay())
        assertThatThrownBy { DorisArrayWireDecoder.decode("""["2021-13-99"]""", DateElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("malformed DATE element")
    }

    @Test
    fun testDatetimeElementsPreserveMicroseconds() {
        val micros6 = TimestampElement(createTimestampType(6))
        assertThat(DorisArrayWireDecoder.decode("""["2021-06-15 12:34:56.789012"]""", micros6))
            .containsExactly(epochMicros(LocalDateTime.of(2021, 6, 15, 12, 34, 56, 789012000)))
        // zero fraction retained on the wire and decoded exactly
        assertThat(DorisArrayWireDecoder.decode("""["0000-01-01 00:00:00.000000"]""", micros6))
            .containsExactly(epochMicros(LocalDateTime.of(0, 1, 1, 0, 0, 0, 0)))
        val seconds = TimestampElement(createTimestampType(0))
        assertThat(DorisArrayWireDecoder.decode("""["9999-12-31 23:59:59"]""", seconds))
            .containsExactly(epochMicros(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 0)))
        val millis = TimestampElement(createTimestampType(3))
        assertThat(DorisArrayWireDecoder.decode("""["2021-06-15 12:34:56.789"]""", millis))
            .containsExactly(epochMicros(LocalDateTime.of(2021, 6, 15, 12, 34, 56, 789000000)))
        // wire fraction finer than the declared precision is outside the grammar
        assertThatThrownBy { DorisArrayWireDecoder.decode("""["2021-06-15 12:34:56.789012"]""", seconds) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("does not fit")
    }

    // --- IPV4/IPV6: quoted canonical text -> IPADDRESS slices ---

    @Test
    fun testIpAddressElements() {
        val element = IpAddressElement(ipAddressType, isV4 = true)
        val values = DorisArrayWireDecoder.decode("""["192.168.1.1", "0.0.0.0", "255.255.255.255"]""", element)
        assertThat(values).containsExactly(
            DorisTypeMapping.ipAddressSlice("192.168.1.1"),
            DorisTypeMapping.ipAddressSlice("0.0.0.0"),
            DorisTypeMapping.ipAddressSlice("255.255.255.255"),
        )
        assertThat(DorisArrayWireDecoder.decode("""["2001:db8::ff00:42:8329", "::", "fe80::1"]""", element))
            .hasSize(3)
        assertThatThrownBy { DorisArrayWireDecoder.decode("""["not-an-ip"]""", element) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("Unreadable Doris IPV4/IPV6")
    }

    // --- nesting: >= 3 levels proven; arbitrary depth supported ---

    @Test
    fun testNestedArrays() {
        val nested2 = NestedArrayElement(IntegerElement)
        assertThat(DorisArrayWireDecoder.decode("[[1, null], null, []]", nested2))
            .containsExactly(listOf(1L, null), null, emptyList<Any?>())

        val nested3 = NestedArrayElement(nested2)
        assertThat(DorisArrayWireDecoder.decode("[[[1, 2], [3]], [[4]]]", nested3))
            .containsExactly(listOf(listOf(1L, 2L), listOf(3L)), listOf(listOf(4L)))

        val nested4 = NestedArrayElement(nested3)
        assertThat(DorisArrayWireDecoder.decode("[[[[1]]], []]", nested4))
            .containsExactly(listOf(listOf(listOf(1L))), emptyList<Any?>())
    }

    // --- spike §5 malformed battery: all fail loud with wire-offset info ---

    @Test
    fun testMalformedInputsFailLoudWithOffset() {
        val malformed = listOf<Pair<String, DorisArrayElement>>(
            "[1, 2" to IntegerElement, // truncated, no ]
            "1, 2]" to IntegerElement, // no leading [
            "[1,2]" to IntegerElement, // separator without space
            "[1, 2] " to IntegerElement, // trailing garbage
            "[1, , 3]" to IntegerElement, // empty element
            "[abc]" to IntegerElement, // non-numeric
            "[999]" to TinyintElement, // overflow
            "[2, true]" to BooleanElement, // boolean not 1/0
            """["2021-13-99"]""" to DateElement, // month 13
            """["unterminated""" to DateElement, // unterminated quote
            """["a"x, "b"]""" to DateElement, // interior quote (alphabet violation)
            "[null" to IntegerElement, // truncated after null
        )
        for ((wire, element) in malformed) {
            assertThatThrownBy { DorisArrayWireDecoder.decode(wire, element) }
                .describedAs(wire)
                .isInstanceOf(TrinoException::class.java)
                .hasMessageContaining("wire offset")
        }
    }

    @Test
    fun testNullBoundaryGuard() {
        // 'null' as a prefix of a longer bare token is not a valid element
        assertThatThrownBy { DorisArrayWireDecoder.decode("[nullx]", IntegerElement) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("not a valid element")
    }

    // --- Block building sanity (element carriers -> Trino Block, including nesting) ---

    @Test
    fun testElementsBlockConstruction() {
        val flat = DorisArrayWireDecoder.elementsBlock(
            IntegerElement,
            DorisArrayWireDecoder.decode("[1, null, 3]", IntegerElement),
        )
        assertThat(flat.positionCount).isEqualTo(3)
        assertThat(flat.isNull(1)).isTrue()

        val nested = NestedArrayElement(IntegerElement)
        val block = DorisArrayWireDecoder.elementsBlock(
            nested,
            DorisArrayWireDecoder.decode("[[1, null], null, []]", nested),
        )
        assertThat(block.positionCount).isEqualTo(3)
        assertThat(block.isNull(1)).isTrue()
    }

    private fun epochMicros(dateTime: LocalDateTime): Long =
        dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L + dateTime.nano / 1_000L
}
