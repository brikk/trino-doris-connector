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

import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.TypeHandlingJdbcConfig
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.spi.type.VarcharType.createVarcharType
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Types
import java.util.Optional

class TestDorisScalarVariable {
    private val session = TestingConnectorSession.builder()
        .setPropertyMetadata(TypeHandlingJdbcSessionProperties(TypeHandlingJdbcConfig()).sessionProperties)
        .setPropertyValues(mapOf("unsupported_type_handling" to "CONVERT_TO_VARCHAR"))
        .build()
    private val mapping = DorisTypeMapping(TESTING_TYPE_MANAGER)
    private val rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
        .add(RewriteScalarVariable(mapping) { "`$it`" })
        .build()

    @Test
    fun testNativeAndSyntheticTypesAreAccepted() {
        for (remoteType in listOf(
            "tinyint(1)", "boolean", "tinyint(4)", "smallint(6)", "int(11)", "bigint(20)",
            "largeint", "decimal(9,2)", "decimalv3(38,10)", "date", "datetime", "datetime(6)",
            "VARCHAR(50)", "string", "text",
        )) {
            val type = mapping.toColumnMapping(session, remoteType).orElseThrow().type
            assertThat(rewrite(remoteType, type)).describedAs(remoteType).isPresent
            // Scalar projections synthesize these handles, which must survive another rewrite pass.
            val syntheticType = DorisScalarValues.dorisTypeFor(type)!!
            assertThat(rewrite(syntheticType, type)).describedAs("synthetic $syntheticType").isPresent
        }
    }

    @Test
    fun testTextRenderingsAndUnknownTypesAreRejected() {
        for (remoteType in listOf(
            "decimal(50,2)", "decimalv3(76,10)", "array<varchar(50)>", "array<array<string>>",
            "map<string,int(11)>", "struct<a:int(11),b:string>", "json", "char(50)", "unknown",
        )) {
            assertThat(rewrite(remoteType, VARCHAR)).describedAs(remoteType).isEmpty
        }
        assertThat(rewrite(null, VARCHAR)).isEmpty
        assertThat(rewrite("unknown", INTEGER)).isEmpty
    }

    @Test
    fun testMismatchedNativeTypesAreRejected() {
        for ((remoteType, type) in listOf(
            "int(11)" to BIGINT,
            "tinyint(4)" to BOOLEAN,
            "decimal(9,2)" to createDecimalType(9, 3),
            "decimalv3(38,10)" to createDecimalType(18, 10),
            "largeint" to createDecimalType(38, 1),
            "datetime(6)" to createTimestampType(3),
            "varchar(50)" to createVarcharType(100),
        )) {
            assertThat(rewrite(remoteType, type)).describedAs("$remoteType as $type").isEmpty
        }
        assertThat(rewrite("int(11)", INTEGER, BIGINT)).isEmpty
    }

    private fun rewrite(remoteType: String?, type: Type, columnType: Type = type) = rewriter.rewrite(
        session,
        Variable("value", type),
        mapOf(
            "value" to JdbcColumnHandle.builder()
                .setColumnName("_pfgnrtd_0")
                .setJdbcTypeHandle(
                    remoteType?.let(DorisTypeMapping::toTypeHandle) ?: JdbcTypeHandle(
                        Types.OTHER, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    ),
                )
                .setColumnType(columnType)
                .build(),
        ),
    )
}
