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

import dev.brikk.trino.doris.DorisPushdownControllers.CHAR_PUSHDOWN
import dev.brikk.trino.doris.DorisPushdownControllers.IPV4_PUSHDOWN
import dev.brikk.trino.doris.DorisPushdownControllers.TEMPORAL_PUSHDOWN
import dev.brikk.trino.doris.DorisPushdownControllers.VARCHAR_PUSHDOWN
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.DoubleReadFunction
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.LongReadFunction
import io.trino.plugin.jdbc.ObjectReadFunction
import io.trino.plugin.jdbc.ObjectWriteFunction
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN
import io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.charReadFunction
import io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.dateWriteFunctionUsingLocalDate
import io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.jsonColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.realWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.timestampWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.toTrinoTimestamp
import io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction
import io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling
import io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE
import io.trino.spi.TrinoException
import io.trino.spi.block.Block
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.type.ArrayType
import io.trino.spi.type.CharType.createCharType
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.Decimals.MAX_PRECISION
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Int128
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.StandardTypes
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.spi.type.Type
import io.trino.spi.type.TypeDescriptor
import io.trino.spi.type.TypeManager
import io.trino.spi.type.VarcharType.createUnboundedVarcharType
import io.trino.spi.type.VarcharType.createVarcharType
import java.lang.Float.floatToRawIntBits
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.format.DateTimeParseException
import java.util.Optional

/**
 * Native scalar/array mappings; retained type evidence: `dev-docs/archive/LEDGER-p0-type-and-capability.md` §A.
 * Every mapping keys off the Doris `information_schema.columns.COLUMN_TYPE` string carried
 * in [JdbcTypeHandle.jdbcTypeName]; JDBC metadata type codes are never trusted (PROBE §1).
 *
 * Supported arrays use the closed leaf allowlist below. MAP/STRUCT and denied arrays
 * are hidden or explicitly textualized; opaque engine states stay hidden under all policies.
 */
internal class DorisTypeMapping(typeManager: TypeManager) {
    private val jsonType: Type = typeManager.getType(TypeDescriptor(StandardTypes.JSON))
    private val ipAddressType: Type = typeManager.getType(TypeDescriptor(StandardTypes.IPADDRESS))

    private val mappers: Map<String, (DorisColumnType) -> Optional<ColumnMapping>> = buildMap {
        // Doris BOOLEAN reports COLUMN_TYPE `tinyint(1)`; a real TINYINT reports `tinyint(4)`.
        // Discrimination MUST come from COLUMN_TYPE — protocol-level metadata cannot tell them
        // apart under tinyInt1isBit=false (PROBE §2/§4, Impl #7; ledger §A row `boolean`).
        put("boolean") { _ -> Optional.of(booleanColumnMapping()) }
        put("tinyint") { parsed ->
            Optional.of(if (parsed.arguments == listOf(1)) booleanColumnMapping() else tinyintColumnMapping())
        }
        put("smallint") { _ -> Optional.of(smallintColumnMapping()) }
        put("int") { _ -> Optional.of(integerColumnMapping()) }
        put("bigint") { _ -> Optional.of(bigintColumnMapping()) }
        put("largeint") { _ -> Optional.of(largeintColumnMapping()) }
        put("float") { _ -> Optional.of(dorisFloatColumnMapping()) }
        put("double") { _ -> Optional.of(dorisDoubleColumnMapping()) }
        put("decimal") { parsed -> Optional.of(decimalMapping(parsed)) }
        put("decimalv3") { parsed -> Optional.of(decimalMapping(parsed)) }
        put("date") { _ -> Optional.of(dorisDateColumnMapping()) }
        put("datetime") { parsed -> Optional.of(dorisTimestampColumnMapping(parsed)) }
        put("char") { parsed -> Optional.of(charMapping(parsed)) }
        put("varchar") { parsed -> Optional.of(varcharMapping(parsed)) }
        put("string") { _ -> Optional.of(unboundedVarcharMapping()) }
        put("text") { _ -> Optional.of(unboundedVarcharMapping()) }
        put("json") { _ -> Optional.of(jsonMapping()) }
        put("variant") { _ -> Optional.of(jsonMapping()) }
        put("ipv4") { _ -> Optional.of(ipAddressMapping(isV4 = true)) }
        put("ipv6") { _ -> Optional.of(ipAddressMapping(isV4 = false)) }
        put("array") { parsed -> arrayColumnMapping(parsed) }
    }

    /**
     * The ARRAY element allowlist (ledger §A "ARRAY element allowlist verdict"; spike §1/§4):
     * a resolver returning null means the element type is DENIED — string-family leaves are
     * provably ambiguous on the wire (zero escaping, spike F4), JSON is not creatable (F5),
     * MAP/STRUCT/VARIANT are separate wire work, and DECIMAL(>38) exceeds Trino's ceiling.
     * A nested array whose (possibly deep) leaf is denied inherits the denial (spike §7.3).
     */
    private val arrayElementResolvers: Map<String, (DorisColumnType) -> DorisArrayElement?> = buildMap {
        put("boolean") { _ -> DorisArrayElement.BooleanElement }
        put("tinyint") { parsed ->
            if (parsed.arguments == listOf(1)) DorisArrayElement.BooleanElement else DorisArrayElement.TinyintElement
        }
        put("smallint") { _ -> DorisArrayElement.SmallintElement }
        put("int") { _ -> DorisArrayElement.IntegerElement }
        put("bigint") { _ -> DorisArrayElement.BigintElement }
        put("largeint") { _ -> DorisArrayElement.LargeintElement }
        put("float") { _ -> DorisArrayElement.RealElement }
        put("double") { _ -> DorisArrayElement.DoubleElement }
        put("decimal") { parsed -> decimalArrayElement(parsed) }
        put("decimalv3") { parsed -> decimalArrayElement(parsed) }
        put("date") { _ -> DorisArrayElement.DateElement }
        put("datetime") { parsed -> DorisArrayElement.TimestampElement(timestampType(parsed)) }
        put("ipv4") { _ -> DorisArrayElement.IpAddressElement(ipAddressType, isV4 = true) }
        put("ipv6") { _ -> DorisArrayElement.IpAddressElement(ipAddressType, isV4 = false) }
        put("array") { parsed ->
            resolveArrayElement(arrayElementColumnType(parsed.raw))?.let { DorisArrayElement.NestedArrayElement(it) }
        }
    }

    /**
     * Returns the Trino column mapping for a Doris COLUMN_TYPE string, or empty when the type
     * is not exposed (unsupported-type policy: column hidden).
     *
     * `unsupported-type-handling=CONVERT_TO_VARCHAR` is honored exactly where the ledger
     * permits a VARCHAR-of-wire-text policy: denied-leaf ARRAY (ledger §A "unsupported column,
     * or VARCHAR-of-whole-array-text") and MAP/STRUCT ("unsupported or VARCHAR-of-text"). The
     * wire text of all three is a proven server-rendered grammar (PROBE §"wire-format").
     * Allowlisted ARRAY element types map NATIVELY regardless of the policy. Opaque engine
     * states (BITMAP/HLL/QUANTILE_STATE/AGG_STATE) stay hidden under EVERY policy — their
     * "text" is NULL or raw state bytes, never meaningful (PROBE Impl #9).
     */
    fun toColumnMapping(session: ConnectorSession, columnType: String): Optional<ColumnMapping> {
        val parsed = DorisColumnType.parse(columnType)
        val mapping = mappers[parsed.baseName]?.invoke(parsed) ?: Optional.empty()
        if (mapping.isPresent) {
            return mapping
        }
        if (parsed.baseName in TEXT_SAFE_COMPLEX_TYPES && getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return Optional.of(unboundedVarcharTextMapping())
        }
        return Optional.empty()
    }

    /**
     * Native `ARRAY<T>` for the allowlist: `getString()` wire text -> strict
     * [DorisArrayWireDecoder] -> Trino elements Block (PostgreSqlClient Block-construction
     * pattern only — Connector/J implements no `java.sql.Array` for Doris, spike §6).
     * Typed ARRAY predicates are admitted separately by DorisArrayPushdownSupport.
     */
    private fun arrayColumnMapping(parsed: DorisColumnType): Optional<ColumnMapping> {
        val element = resolveArrayElement(arrayElementColumnType(parsed.raw)) ?: return Optional.empty()
        return Optional.of(
            ColumnMapping.objectMapping(
                ArrayType(element.trinoType),
                ObjectReadFunction.of(Block::class.java) { resultSet, columnIndex ->
                    val wire = resultSet.getString(columnIndex)
                        ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "ARRAY read function called on a NULL array value")
                    DorisArrayWireDecoder.elementsBlock(element, DorisArrayWireDecoder.decode(wire, element))
                },
                readOnlyArrayWriteFunction(),
                DISABLE_PUSHDOWN,
            ),
        )
    }

    /** True for the Trino IPADDRESS type (both Doris IPV4 and IPV6 map to it). */
    fun isIpAddress(type: Type): Boolean = type == ipAddressType

    internal fun resolveArrayElement(columnType: String): DorisArrayElement? {
        val parsed = DorisColumnType.parse(columnType)
        return arrayElementResolvers[parsed.baseName]?.invoke(parsed)
    }

    private fun decimalArrayElement(parsed: DorisColumnType): DorisArrayElement? {
        val (precision, scale) = decimalPrecisionAndScale(parsed)
        if (precision > MAX_PRECISION) {
            // Decimal256 array elements exceed Trino's DECIMAL ceiling -> denied leaf.
            return null
        }
        return DorisArrayElement.DecimalElement(createDecimalType(precision, scale))
    }

    /**
     * Read-only VARCHAR view of wire text: Decimal256 always, complex types when opted in via
     * `unsupported-type-handling=CONVERT_TO_VARCHAR`. No pushdown: the text is a rendering,
     * not a comparable value.
     */
    private fun unboundedVarcharTextMapping(): ColumnMapping {
        val varcharType = createUnboundedVarcharType()
        return ColumnMapping.sliceMapping(varcharType, varcharReadFunction(varcharType), varcharWriteFunction(), DISABLE_PUSHDOWN)
    }

    /**
     * DECIMAL(p<=38, s) maps exactly; 38 < p (Decimal256) maps to VARCHAR carrying the exact
     * wire text — the read path IS wire-exact, the limit is Trino's public DECIMAL ceiling of
     * 38, and the rejected alternative (MAP_TO_NUMBER) silently loses scale (ledger §A
     * "Decimal256 stance"; PROBE Impl #3).
     */
    private fun decimalMapping(parsed: DorisColumnType): ColumnMapping {
        val (precision, scale) = decimalPrecisionAndScale(parsed)
        if (precision > MAX_PRECISION) {
            return unboundedVarcharTextMapping()
        }
        return decimalColumnMapping(createDecimalType(precision, scale))
    }

    private fun decimalPrecisionAndScale(parsed: DorisColumnType): Pair<Int, Int> {
        if (parsed.arguments.size != 2) {
            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris decimal COLUMN_TYPE: '${parsed.raw}'")
        }
        return parsed.arguments[0] to parsed.arguments[1]
    }

    private fun charMapping(parsed: DorisColumnType): ColumnMapping {
        val length = parsed.arguments.singleOrNull()
            ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris char COLUMN_TYPE: '${parsed.raw}'")
        val charType = createCharType(length)
        return ColumnMapping.sliceMapping(charType, charReadFunction(charType), charWriteFunction(), CHAR_PUSHDOWN)
    }

    private fun varcharMapping(parsed: DorisColumnType): ColumnMapping {
        val length = parsed.arguments.singleOrNull()
            ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris varchar COLUMN_TYPE: '${parsed.raw}'")
        val varcharType = createVarcharType(length)
        return ColumnMapping.sliceMapping(varcharType, varcharReadFunction(varcharType), varcharWriteFunction(), VARCHAR_PUSHDOWN)
    }

    private fun unboundedVarcharMapping(): ColumnMapping {
        val varcharType = createUnboundedVarcharType()
        return ColumnMapping.sliceMapping(varcharType, varcharReadFunction(varcharType), varcharWriteFunction(), VARCHAR_PUSHDOWN)
    }

    /** JSON and VARIANT: strict text-to-native-Json parsing, no blind pushdown (ledger §A; SR K14). */
    private fun jsonMapping(): ColumnMapping {
        return jsonColumnMapping(jsonType, varcharWriteFunction())
    }

    /**
     * IPV4/IPV6 arrive as canonical text (`192.168.1.1`, `2001:db8::ff00:42:8329`, `::`,
     * `fe80::1` — PROBE §3, Impl #4) and map to Trino IPADDRESS; unparseable text fails loud.
     *
     * Pushdown is TYPE-AWARE (live-probed 2026-07-19, `dev-docs/archive/REPORT-ip-pushdown-probe-4.1.3.md`):
     * Trino IPADDRESS is a single 16-byte (IPv4-mapped) type, but Doris has DISTINCT IPV4 and
     * IPV6 types that reject each other's textual forms — IPV4 wants dotted-quad `a.b.c.d`,
     * IPV6 wants a colon-hex form and REJECTS dotted-quad. Byte ordering is identical on both
     * engines (unsigned big-endian 16 bytes — [IpAddressType.comparisonOperator] vs Doris
     * IPV4/IPV6 order), so equality/range/IN domains and TopN are exact once rendered in the
     * TARGET dialect. Bound parameters render via [DorisIpAddress.writeFunction]:
     * - IPV4 columns: [IPV4_PUSHDOWN] pushes ONLY when every domain value is an IPv4-mapped
     *   address (a real IPv6 literal can never equal an IPV4 column value; kept local — still
     *   correct) so the write function can always emit dotted-quad.
     * - IPV6 columns: FULL_PUSHDOWN — every 16-byte value renders as a fully-expanded 8-group
     *   colon-hex literal Doris accepts (incl. `0:0:0:0:0:ffff:c0a8:101` for mapped v4).
     */
    private fun ipAddressMapping(isV4: Boolean): ColumnMapping {
        return ColumnMapping.sliceMapping(
            ipAddressType,
            { resultSet, columnIndex -> DorisIpAddress.parse(resultSet.getString(columnIndex)) },
            DorisIpAddress.writeFunction(isV4),
            if (isV4) IPV4_PUSHDOWN else FULL_PUSHDOWN,
        )
    }

    companion object {
        /** Complex types whose wire text is a proven server-rendered grammar (PROBE §"wire-format"). */
        private val TEXT_SAFE_COMPLEX_TYPES = setOf("array", "map", "struct")

        internal const val MAX_DATETIME_PRECISION = 6

        /** Max unscaled value representable in DECIMAL(38,0): 10^38 - 1. */
        private val DECIMAL_38_MAX: BigInteger = BigInteger.TEN.pow(MAX_PRECISION).subtract(BigInteger.ONE)

        /**
         * LARGEINT (signed 128-bit) -> DECIMAL(38,0): wire value is TEXT (RSMD CHAR), so read
         * `getString` and parse BigInteger — `getBigDecimal` throws on the negative extreme
         * (PROBE §2/§3, Impl #2). Out-of-DECIMAL(38,0) range fails LOUD, never clamps
         * (ledger §A "LARGEINT string-read + fail-loud rule"). Domain pushdown is safe because
         * every value in a DECIMAL(38,0) domain is representable by construction.
         */
        internal fun largeintColumnMapping(): ColumnMapping {
            val decimalType = createDecimalType(MAX_PRECISION, 0)
            return ColumnMapping.objectMapping(
                decimalType,
                ObjectReadFunction.of(Int128::class.java) { resultSet, columnIndex ->
                    parseLargeint(resultSet.getString(columnIndex))
                },
                longDecimalWriteFunction(decimalType),
                FULL_PUSHDOWN,
            )
        }

        internal fun parseLargeint(text: String): Int128 {
            val value = try {
                BigInteger(text)
            } catch (e: NumberFormatException) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris LARGEINT wire value: '$text'", e)
            }
            if (value.abs() > DECIMAL_38_MAX) {
                throw TrinoException(
                    NUMERIC_VALUE_OUT_OF_RANGE,
                    "Doris LARGEINT value out of DECIMAL(38, 0) range (fail-loud per type contract, never clamped): $value",
                )
            }
            return Int128.valueOf(value)
        }

        /** FLOAT -> REAL via text: Connector/J getFloat/getObject reject infinities, including during NULL detection. */
        private fun dorisFloatColumnMapping(): ColumnMapping {
            return ColumnMapping.longMapping(
                REAL,
                object : LongReadFunction {
                    override fun isNull(resultSet: ResultSet, columnIndex: Int): Boolean {
                        resultSet.getString(columnIndex)
                        return resultSet.wasNull()
                    }

                    override fun readLong(resultSet: ResultSet, columnIndex: Int): Long {
                        val text = resultSet.getString(columnIndex)
                        try {
                            return floatToRawIntBits(text.toFloat()).toLong()
                        } catch (e: NumberFormatException) {
                            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris FLOAT wire value: '$text'", e)
                        }
                    }
                },
                realWriteFunction(),
                DISABLE_PUSHDOWN,
            )
        }

        /**
         * DOUBLE reads via `getString` + parse: `getDouble`/`getObject` THROW
         * `SQLDataException` on `Infinity`/`-Infinity` wire values, which would turn one
         * poisoned row into a whole-scan failure (PROBE §3, Impl #6; ledger §A row `double`).
         * `Double.parseDouble` accepts `Infinity`/`-Infinity`/`NaN`, so those values surface
         * faithfully; anything else unparseable fails loud. Pushdown disabled: approximate type.
         */
        private fun dorisDoubleColumnMapping(): ColumnMapping {
            return ColumnMapping.doubleMapping(
                DOUBLE,
                object : DoubleReadFunction {
                    override fun isNull(resultSet: ResultSet, columnIndex: Int): Boolean {
                        resultSet.getString(columnIndex)
                        return resultSet.wasNull()
                    }

                    override fun readDouble(resultSet: ResultSet, columnIndex: Int): Double {
                        val text = resultSet.getString(columnIndex)
                        try {
                            return text.toDouble()
                        } catch (e: NumberFormatException) {
                            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris DOUBLE wire value: '$text'", e)
                        }
                    }
                },
                doubleWriteFunction(),
                DISABLE_PUSHDOWN,
            )
        }

        /**
         * DATE reads via `getString` -> strict ISO LocalDate: `getDate`/`getObject` throw
         * `SQLException: YEAR` on Doris's `0000-01-01` minimum (PROBE §3, Impl #6;
         * ledger §A row `date`). Domain pushdown allowed (exact).
         */
        private fun dorisDateColumnMapping(): ColumnMapping {
            return ColumnMapping.longMapping(
                DATE,
                object : LongReadFunction {
                    override fun isNull(resultSet: ResultSet, columnIndex: Int): Boolean {
                        resultSet.getString(columnIndex)
                        return resultSet.wasNull()
                    }

                    override fun readLong(resultSet: ResultSet, columnIndex: Int): Long {
                        val text = resultSet.getString(columnIndex)
                        try {
                            return LocalDate.parse(text, ISO_LOCAL_DATE).toEpochDay()
                        } catch (e: DateTimeParseException) {
                            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris DATE wire value: '$text'", e)
                        }
                    }
                },
                dateWriteFunctionUsingLocalDate(),
                TEMPORAL_PUSHDOWN,
            )
        }

        /**
         * DATETIME(p), p<=6 -> TIMESTAMP(p), read via `getObject(LocalDateTime)` (zoneless,
         * tolerates `0000-*`); NEVER `getTimestamp`, which shifts the instant by the session
         * zone and throws `YEAR` on `0000-*` (PROBE §3/§6, Impl #5; ledger §A "DATETIME
         * read-via-LocalDateTime rule"). Max proven precision is 6 (microseconds).
         */
        internal fun timestampType(parsed: DorisColumnType): TimestampType {
            val precision = parsed.arguments.firstOrNull() ?: 0
            if (precision !in 0..MAX_DATETIME_PRECISION) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Unsupported Doris DATETIME precision (max proven is 6): '${parsed.raw}'")
            }
            return createTimestampType(precision)
        }

        private fun dorisTimestampColumnMapping(parsed: DorisColumnType): ColumnMapping {
            val timestampType = timestampType(parsed)
            return ColumnMapping.longMapping(
                timestampType,
                object : LongReadFunction {
                    override fun isNull(resultSet: ResultSet, columnIndex: Int): Boolean {
                        resultSet.getObject(columnIndex, LocalDateTime::class.java)
                        return resultSet.wasNull()
                    }

                    override fun readLong(resultSet: ResultSet, columnIndex: Int): Long {
                        return toTrinoTimestamp(timestampType, resultSet.getObject(columnIndex, LocalDateTime::class.java))
                    }
                },
                timestampWriteFunction(timestampType),
                TEMPORAL_PUSHDOWN,
            )
        }

        /**
         * Extracts the element COLUMN_TYPE from an `array<...>` COLUMN_TYPE string, nesting-
         * aware (`array<array<int(11)>>` -> `array<int(11)>`); malformed text fails loud.
         */
        internal fun arrayElementColumnType(raw: String): String {
            val trimmed = raw.trim()
            val open = trimmed.indexOf('<')
            if (open <= 0 || !trimmed.endsWith(">") || open >= trimmed.length - 1) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Malformed Doris ARRAY COLUMN_TYPE: '$raw'")
            }
            return trimmed.substring(open + 1, trimmed.length - 1)
        }

        /** Read-only connector: array values are never written or bound as parameters (pushdown disabled). */
        private fun readOnlyArrayWriteFunction(): ObjectWriteFunction =
            ObjectWriteFunction.of(Block::class.java) { _, _, _ ->
                throw TrinoException(NOT_SUPPORTED, "The Doris connector is read-only and does not allow any mutating operation")
            }

        /**
         * Builds the [JdbcTypeHandle] for a COLUMN_TYPE string. The COLUMN_TYPE string itself
         * rides in `jdbcTypeName` and is the ONLY value type decisions key off; the synthesized
         * `java.sql.Types` code and sizes exist for diagnostics.
         */
        private val DIAGNOSTIC_JDBC_TYPES: Map<String, Int> = mapOf(
            "boolean" to Types.BOOLEAN,
            "tinyint" to Types.TINYINT,
            "smallint" to Types.SMALLINT,
            "int" to Types.INTEGER,
            "bigint" to Types.BIGINT,
            "largeint" to Types.BIGINT,
            "float" to Types.REAL,
            "double" to Types.DOUBLE,
            "decimal" to Types.DECIMAL,
            "decimalv3" to Types.DECIMAL,
            "date" to Types.DATE,
            "datetime" to Types.TIMESTAMP,
            "char" to Types.CHAR,
            "varchar" to Types.VARCHAR,
            "string" to Types.VARCHAR,
            "text" to Types.VARCHAR,
            "json" to Types.LONGVARCHAR,
            "variant" to Types.LONGVARCHAR,
        )

        internal fun toTypeHandle(columnType: String): JdbcTypeHandle {
            val parsed = DorisColumnType.parse(columnType)
            val jdbcType = DIAGNOSTIC_JDBC_TYPES[parsed.baseName] ?: Types.OTHER
            return JdbcTypeHandle(
                jdbcType,
                Optional.of(parsed.raw),
                Optional.ofNullable(parsed.arguments.getOrNull(0)),
                Optional.ofNullable(parsed.arguments.getOrNull(1)),
                Optional.empty(),
                Optional.empty(),
            )
        }

        /** True when a Doris DECIMAL is exposed as VARCHAR because it exceeds Trino's precision ceiling. */
        internal fun isDecimal256(columnType: String): Boolean {
            val parsed = DorisColumnType.parse(columnType)
            return (parsed.baseName == "decimal" || parsed.baseName == "decimalv3") &&
                parsed.arguments.firstOrNull()?.let { it > MAX_PRECISION } == true
        }
    }
}
