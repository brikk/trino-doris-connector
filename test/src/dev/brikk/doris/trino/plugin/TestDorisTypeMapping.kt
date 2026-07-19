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

import io.trino.plugin.jdbc.TypeHandlingJdbcConfig
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties
import io.trino.plugin.jdbc.UnsupportedTypeHandling
import io.trino.spi.TrinoException
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.CharType.createCharType
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Int128
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.createUnboundedVarcharType
import io.trino.spi.type.VarcharType.createVarcharType
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** Unit tests for the COLUMN_TYPE parser and the ledger §A scalar type contract. No cluster needed. */
class TestDorisTypeMapping {
    private val mapping = DorisTypeMapping(TESTING_TYPE_MANAGER)

    private val session: ConnectorSession = sessionWithTypeHandling(null)
    private val convertToVarcharSession: ConnectorSession = sessionWithTypeHandling(UnsupportedTypeHandling.CONVERT_TO_VARCHAR)

    private fun sessionWithTypeHandling(handling: UnsupportedTypeHandling?): ConnectorSession {
        val builder = TestingConnectorSession.builder()
            .setPropertyMetadata(TypeHandlingJdbcSessionProperties(TypeHandlingJdbcConfig()).sessionProperties)
        handling?.let { builder.setPropertyValues(mapOf(TypeHandlingJdbcSessionProperties.UNSUPPORTED_TYPE_HANDLING to it.name)) }
        return builder.build()
    }

    // --- COLUMN_TYPE parsing ---

    @Test
    fun testParse() {
        assertThat(DorisColumnType.parse("tinyint(1)")).isEqualTo(DorisColumnType("tinyint", listOf(1), "tinyint(1)"))
        assertThat(DorisColumnType.parse("largeint")).isEqualTo(DorisColumnType("largeint", listOf(), "largeint"))
        assertThat(DorisColumnType.parse("decimalv3(38, 10)")).isEqualTo(DorisColumnType("decimalv3", listOf(38, 10), "decimalv3(38, 10)"))
        assertThat(DorisColumnType.parse("datetime")).isEqualTo(DorisColumnType("datetime", listOf(), "datetime"))
        assertThat(DorisColumnType.parse("datetime(6)")).isEqualTo(DorisColumnType("datetime", listOf(6), "datetime(6)"))
        assertThat(DorisColumnType.parse("array<int(11)>").baseName).isEqualTo("array")
        assertThat(DorisColumnType.parse("map<string,int(11)>").baseName).isEqualTo("map")
        assertThat(DorisColumnType.parse("struct<int(11),string>").baseName).isEqualTo("struct")
        assertThat(DorisColumnType.parse("array<array<largeint>>").baseName).isEqualTo("array")
        assertThat(DorisColumnType.parse("VARCHAR(100)")).isEqualTo(DorisColumnType("varchar", listOf(100), "VARCHAR(100)"))
    }

    @Test
    fun testParseMalformedFailsLoud() {
        assertThatThrownBy { DorisColumnType.parse("decimalv3(38") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("unterminated")
        assertThatThrownBy { DorisColumnType.parse("varchar(abc)") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("Malformed")
    }

    // --- ledger §A type contract ---

    @Test
    fun testScalarMappings() {
        assertMappedTo("tinyint(1)", BOOLEAN) // Doris BOOLEAN
        assertMappedTo("boolean", BOOLEAN)
        assertMappedTo("tinyint(4)", TINYINT)
        assertMappedTo("tinyint", TINYINT)
        assertMappedTo("bigint(20)", BIGINT)
        assertMappedTo("largeint", createDecimalType(38, 0))
        assertMappedTo("float", REAL)
        assertMappedTo("double", DOUBLE)
        assertMappedTo("decimalv3(9, 2)", createDecimalType(9, 2))
        assertMappedTo("decimalv3(38, 10)", createDecimalType(38, 10))
        // Decimal256 -> VARCHAR (Trino DECIMAL ceiling is 38; wire text is exact)
        assertMappedTo("decimalv3(76, 10)", createUnboundedVarcharType())
        assertMappedTo("date", DATE)
        assertMappedTo("datetime", createTimestampType(0))
        assertMappedTo("datetime(3)", createTimestampType(3))
        assertMappedTo("datetime(6)", createTimestampType(6))
        assertMappedTo("char(10)", createCharType(10))
        assertMappedTo("varchar(100)", createVarcharType(100))
        assertMappedTo("string", createUnboundedVarcharType())
        assertThat(mapping.toColumnMapping(session, "json").orElseThrow().type.baseName).isEqualTo("json")
        assertThat(mapping.toColumnMapping(session, "variant").orElseThrow().type.baseName).isEqualTo("json")
        assertThat(mapping.toColumnMapping(session, "ipv4").orElseThrow().type.baseName).isEqualTo("ipaddress")
        assertThat(mapping.toColumnMapping(session, "ipv6").orElseThrow().type.baseName).isEqualTo("ipaddress")
    }

    @Test
    fun testUnsupportedTypesAreHidden() {
        // ARRAY comes in P2; MAP/STRUCT deferred; opaque engine states hidden (ledger §A).
        for (columnType in listOf(
            "array<int(11)>",
            "array<varchar(50)>",
            "map<string,int(11)>",
            "struct<int(11),string>",
            "bitmap",
            "hll",
            "quantile_state",
            "agg_state",
            "unknown",
        )) {
            assertThat(mapping.toColumnMapping(session, columnType)).isEmpty()
        }
    }

    @Test
    fun testConvertToVarcharExposesTextSafeComplexTypesOnly() {
        // Ledger §A permits VARCHAR-of-wire-text for ARRAY/MAP/STRUCT; opaque engine states
        // stay hidden under EVERY policy (their "text" is NULL or raw state bytes).
        for (columnType in listOf("array<int(11)>", "array<varchar(50)>", "map<string,int(11)>", "struct<int(11),string>")) {
            val columnMapping = mapping.toColumnMapping(convertToVarcharSession, columnType)
            assertThat(columnMapping).describedAs(columnType).isPresent
            assertThat(columnMapping.orElseThrow().type).describedAs(columnType).isEqualTo(createUnboundedVarcharType())
        }
        for (columnType in listOf("bitmap", "hll", "quantile_state", "agg_state", "unknown")) {
            assertThat(mapping.toColumnMapping(convertToVarcharSession, columnType)).describedAs(columnType).isEmpty()
        }
    }

    @Test
    fun testDatetimePrecisionAboveSixFailsLoud() {
        assertThatThrownBy { mapping.toColumnMapping(session, "datetime(7)") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("max proven is 6")
    }

    // --- LARGEINT fail-loud parsing (ledger §A LARGEINT rule) ---

    @Test
    fun testParseLargeintBounds() {
        val max38 = "99999999999999999999999999999999999999"
        assertThat(DorisTypeMapping.parseLargeint(max38)).isEqualTo(Int128.valueOf(BigInteger(max38)))
        assertThat(DorisTypeMapping.parseLargeint("-$max38")).isEqualTo(Int128.valueOf(BigInteger("-$max38")))
        assertThat(DorisTypeMapping.parseLargeint("0")).isEqualTo(Int128.valueOf(0))

        // The Doris LARGEINT extremes +/-(2^127-1) exceed DECIMAL(38,0): fail loud, never clamp.
        assertThatThrownBy { DorisTypeMapping.parseLargeint("170141183460469231731687303715884105727") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("out of DECIMAL(38, 0) range")
        assertThatThrownBy { DorisTypeMapping.parseLargeint("-170141183460469231731687303715884105727") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("out of DECIMAL(38, 0) range")
        assertThatThrownBy { DorisTypeMapping.parseLargeint("not a number") }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("Unreadable Doris LARGEINT")
    }

    private fun assertMappedTo(columnType: String, expected: Type) {
        val columnMapping = mapping.toColumnMapping(session, columnType)
        assertThat(columnMapping).describedAs(columnType).isPresent
        assertThat(columnMapping.orElseThrow().type).describedAs(columnType).isEqualTo(expected)
    }
}
