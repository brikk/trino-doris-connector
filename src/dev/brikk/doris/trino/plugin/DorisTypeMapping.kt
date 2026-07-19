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
package dev.brikk.doris.trino.plugin

import com.google.common.net.InetAddresses
import io.airlift.slice.Slice
import io.airlift.slice.Slices.utf8Slice
import io.airlift.slice.Slices.wrappedBuffer
import io.trino.plugin.base.util.JsonTypeUtil.jsonParse
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.LongReadFunction
import io.trino.plugin.jdbc.ObjectReadFunction
import io.trino.plugin.jdbc.PredicatePushdownController
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult
import io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN
import io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.charReadFunction
import io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.dateWriteFunctionUsingLocalDate
import io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping
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
import io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE
import io.trino.spi.TrinoException
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.predicate.Domain
import io.trino.spi.type.CharType.createCharType
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Int128
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.StandardTypes
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.spi.type.Type
import io.trino.spi.type.TypeDescriptor
import io.trino.spi.type.TypeManager
import io.trino.spi.type.VarcharType.createUnboundedVarcharType
import io.trino.spi.type.VarcharType.createVarcharType
import java.lang.Float.floatToRawIntBits
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.format.DateTimeParseException
import java.util.Optional

/**
 * Scalar type contract v1, implemented EXACTLY per `LEDGER-p0-type-and-capability.md` §A.
 * Every mapping keys off the Doris `information_schema.columns.COLUMN_TYPE` string carried
 * in [JdbcTypeHandle.jdbcTypeName]; JDBC metadata type codes are never trusted (PROBE §1).
 *
 * Not exposed in P1a (columns hidden, never silently textualized): ARRAY (P2), MAP/STRUCT
 * (deferred), BITMAP/HLL/QUANTILE_STATE/AGG_STATE (opaque engine states, PROBE "BITMAP/HLL").
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
        put("ipv4") { _ -> Optional.of(ipAddressMapping()) }
        put("ipv6") { _ -> Optional.of(ipAddressMapping()) }
    }

    /**
     * Returns the Trino column mapping for a Doris COLUMN_TYPE string, or empty when the type
     * is not exposed (unsupported-type policy: column hidden).
     *
     * `unsupported-type-handling=CONVERT_TO_VARCHAR` is honored exactly where the ledger
     * permits a VARCHAR-of-wire-text policy: ARRAY (ledger §A "unsupported column, or
     * VARCHAR-of-whole-array-text") and MAP/STRUCT ("unsupported or VARCHAR-of-text"). The
     * wire text of all three is a proven server-rendered grammar (PROBE §"wire-format").
     * Opaque engine states (BITMAP/HLL/QUANTILE_STATE/AGG_STATE) stay hidden under EVERY
     * policy — their "text" is NULL or raw state bytes, never meaningful (PROBE Impl #9).
     */
    fun toColumnMapping(session: ConnectorSession, columnType: String): Optional<ColumnMapping> {
        val parsed = DorisColumnType.parse(columnType)
        val mapper = mappers[parsed.baseName]
        if (mapper != null) {
            return mapper(parsed)
        }
        if (parsed.baseName in TEXT_SAFE_COMPLEX_TYPES && getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return Optional.of(unboundedVarcharTextMapping())
        }
        return Optional.empty()
    }

    /**
     * Read-only VARCHAR view of a complex type's wire text (opt-in via
     * `unsupported-type-handling=CONVERT_TO_VARCHAR`). No pushdown: the text is a rendering,
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
        if (parsed.arguments.size != 2) {
            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris decimal COLUMN_TYPE: '${parsed.raw}'")
        }
        val (precision, scale) = parsed.arguments
        if (precision > MAX_TRINO_DECIMAL_PRECISION) {
            return unboundedVarcharMapping()
        }
        return decimalColumnMapping(createDecimalType(precision, scale))
    }

    private fun charMapping(parsed: DorisColumnType): ColumnMapping {
        val length = parsed.arguments.singleOrNull()
            ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris char COLUMN_TYPE: '${parsed.raw}'")
        val charType = createCharType(length)
        return ColumnMapping.sliceMapping(charType, charReadFunction(charType), charWriteFunction(), NULL_ONLY_PUSHDOWN)
    }

    private fun varcharMapping(parsed: DorisColumnType): ColumnMapping {
        val length = parsed.arguments.singleOrNull()
            ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Doris varchar COLUMN_TYPE: '${parsed.raw}'")
        val varcharType = createVarcharType(length)
        return ColumnMapping.sliceMapping(varcharType, varcharReadFunction(varcharType), varcharWriteFunction(), NULL_ONLY_PUSHDOWN)
    }

    private fun unboundedVarcharMapping(): ColumnMapping {
        val varcharType = createUnboundedVarcharType()
        return ColumnMapping.sliceMapping(varcharType, varcharReadFunction(varcharType), varcharWriteFunction(), NULL_ONLY_PUSHDOWN)
    }

    /** JSON and VARIANT: server-normalized JSON text, strict jsonParse, no blind pushdown (ledger §A; SR K14). */
    private fun jsonMapping(): ColumnMapping {
        return ColumnMapping.sliceMapping(
            jsonType,
            { resultSet, columnIndex -> jsonParse(utf8Slice(resultSet.getString(columnIndex))) },
            varcharWriteFunction(),
            DISABLE_PUSHDOWN,
        )
    }

    /**
     * IPV4/IPV6 arrive as canonical text (`192.168.1.1`, `2001:db8::ff00:42:8329`, `::`,
     * `fe80::1` — PROBE §3, Impl #4) and map to Trino IPADDRESS; unparseable text fails loud.
     * Pushdown stays disabled in P1a (only exact numeric/date/boolean domains are pushed);
     * IPADDRESS equality domains are a P1b/P2 item per ledger §A PPD column.
     */
    private fun ipAddressMapping(): ColumnMapping {
        return ColumnMapping.sliceMapping(
            ipAddressType,
            { resultSet, columnIndex -> readIpAddress(resultSet, columnIndex) },
            varcharWriteFunction(),
            DISABLE_PUSHDOWN,
        )
    }

    companion object {
        /** Complex types whose wire text is a proven server-rendered grammar (PROBE §"wire-format"). */
        private val TEXT_SAFE_COMPLEX_TYPES = setOf("array", "map", "struct")

        private const val MAX_TRINO_DECIMAL_PRECISION = 38
        private const val DORIS_MAX_DATETIME_PRECISION = 6
        private const val LARGEINT_DECIMAL_PRECISION = 38

        /** Max unscaled value representable in DECIMAL(38,0): 10^38 - 1. */
        private val DECIMAL_38_MAX: BigInteger = BigInteger.TEN.pow(LARGEINT_DECIMAL_PRECISION).subtract(BigInteger.ONE)

        /**
         * G5 string-domain policy: only null-ness (`IS NULL` / `IS NOT NULL`) is pushed for
         * CHAR/VARCHAR — no equality/range/LIKE until Doris collation is proven. A remote
         * string equality would be at most a superset pre-filter and stock Trino's behavior
         * of pushing it is the exact hazard this policy closes (ledger §A row char/varchar;
         * STOCK pushdown ledger row b).
         */
        internal val NULL_ONLY_PUSHDOWN = PredicatePushdownController { session, domain ->
            if (domain.isOnlyNull || domain.values.isAll) {
                // Null-ness has no collation hazard; pushing it is exact.
                DomainPushdownResult(domain, Domain.all(domain.type))
            } else {
                DISABLE_PUSHDOWN.apply(session, domain)
            }
        }

        /**
         * LARGEINT (signed 128-bit) -> DECIMAL(38,0): wire value is TEXT (RSMD CHAR), so read
         * `getString` and parse BigInteger — `getBigDecimal` throws on the negative extreme
         * (PROBE §2/§3, Impl #2). Out-of-DECIMAL(38,0) range fails LOUD, never clamps
         * (ledger §A "LARGEINT string-read + fail-loud rule"). Domain pushdown is safe because
         * every value in a DECIMAL(38,0) domain is representable by construction.
         */
        internal fun largeintColumnMapping(): ColumnMapping {
            val decimalType = createDecimalType(LARGEINT_DECIMAL_PRECISION, 0)
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

        /** FLOAT -> REAL. Exact-equality pushdown disabled: approximate type (ledger §A; SR K8). */
        private fun dorisFloatColumnMapping(): ColumnMapping {
            return ColumnMapping.longMapping(
                REAL,
                { resultSet, columnIndex -> floatToRawIntBits(resultSet.getFloat(columnIndex)).toLong() },
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
                object : io.trino.plugin.jdbc.DoubleReadFunction {
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
            )
        }

        /**
         * DATETIME(p), p<=6 -> TIMESTAMP(p), read via `getObject(LocalDateTime)` (zoneless,
         * tolerates `0000-*`); NEVER `getTimestamp`, which shifts the instant by the session
         * zone and throws `YEAR` on `0000-*` (PROBE §3/§6, Impl #5; ledger §A "DATETIME
         * read-via-LocalDateTime rule"). Max proven precision is 6 (microseconds).
         */
        private fun dorisTimestampColumnMapping(parsed: DorisColumnType): ColumnMapping {
            val precision = parsed.arguments.firstOrNull() ?: 0
            if (precision !in 0..DORIS_MAX_DATETIME_PRECISION) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Unsupported Doris DATETIME precision (max proven is 6): '${parsed.raw}'")
            }
            val timestampType = createTimestampType(precision)
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
            )
        }

        private fun readIpAddress(resultSet: ResultSet, columnIndex: Int): Slice {
            val text = resultSet.getString(columnIndex)
            val address = try {
                InetAddresses.forString(text)
            } catch (e: IllegalArgumentException) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris IPV4/IPV6 wire value: '$text'", e)
            }
            return wrappedBuffer(*toTrinoIpAddressBytes(address))
        }

        /** Trino IPADDRESS representation: 16 bytes, IPv4 as IPv4-mapped IPv6 (::ffff:a.b.c.d). */
        private fun toTrinoIpAddressBytes(address: InetAddress): ByteArray {
            return when (address) {
                is Inet6Address -> address.address
                is Inet4Address -> ByteArray(IPV6_BYTES).also {
                    it[10] = 0xFF.toByte()
                    it[11] = 0xFF.toByte()
                    address.address.copyInto(it, 12)
                }
                else -> throw TrinoException(GENERIC_INTERNAL_ERROR, "Unsupported InetAddress subtype: ${address.javaClass.name}")
            }
        }

        private const val IPV6_BYTES = 16

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
    }
}
