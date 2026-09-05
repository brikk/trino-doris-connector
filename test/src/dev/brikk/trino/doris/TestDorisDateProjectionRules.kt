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

import io.airlift.slice.Slices.utf8Slice
import io.trino.matching.Captures
import io.trino.plugin.base.projection.ProjectFunctionRewriter
import io.trino.plugin.base.projection.ProjectFunctionRule
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.TypeHandlingJdbcConfig
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorTableHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.CAST_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimestampType.createTimestampType
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class TestDorisDateProjectionRules {
    private val session = TestingConnectorSession.builder()
        .setPropertyMetadata(TypeHandlingJdbcSessionProperties(TypeHandlingJdbcConfig()).sessionProperties)
        .build()
    private val table = object : ConnectorTableHandle {}
    private val timestamp = createTimestampType(6)
    private val source = Variable("planner_symbol", timestamp)
    private val cases = listOf(
        Triple(
            RewriteCastDatetimeToDate(),
            Call(DATE, CAST_FUNCTION_NAME, listOf(source)),
            "CAST(%s AS DATE)",
        ),
        Triple(
            RewriteDateTrunc(),
            Call(timestamp, FunctionName("date_trunc"), listOf(Constant(utf8Slice("day"), VARCHAR), source)),
            "date_trunc(%s, 'day')",
        ),
    )
    private val rewriter = ProjectFunctionRewriter<JdbcExpression, ParameterizedExpression>(
        JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteScalarVariable(DorisTypeMapping(TESTING_TYPE_MANAGER), ::quote))
            .build(),
        cases.map { it.first }.toSet(),
    )

    @Test
    fun testPhysicalAndSyntheticColumnNames() {
        for (columnName in listOf("dt_6", "select", "event at", "event`at", "_pfgnrtd_0")) {
            val column = JdbcColumnHandle(columnName, DorisTypeMapping.toTypeHandle("datetime(6)"), timestamp)
            for ((_, expression, template) in cases) {
                val rewritten = rewriter.rewrite(session, table, expression, mapOf(source.name to column)).orElseThrow()
                assertThat(rewritten.expression).isEqualTo(template.format(quote(columnName)))
                assertThat(rewritten.parameters).isEmpty()
                assertThat(rewritten.jdbcTypeHandle).isEqualTo(
                    DorisTypeMapping.toTypeHandle(if (expression.type == DATE) "date" else "datetime(6)"),
                )
            }
        }
    }

    @Test
    fun testUnrewritableSourceIsRejected() {
        for (remoteType in listOf("string", "unknown", "datetime(3)")) {
            val column = JdbcColumnHandle("dt", DorisTypeMapping.toTypeHandle(remoteType), timestamp)
            for ((_, expression, _) in cases) {
                assertThat(rewriter.rewrite(session, table, expression, mapOf(source.name to column)))
                    .describedAs("$remoteType for $expression").isEmpty
            }
        }
    }

    @Test
    fun testRewrittenParametersArePreserved() {
        val parameters = listOf(QueryParameter(DorisTypeMapping.toTypeHandle("datetime(6)"), timestamp, Optional.of(0L)))
        val context = object : ProjectFunctionRule.RewriteContext<ParameterizedExpression> {
            override fun getAssignments(): Map<String, ColumnHandle> = emptyMap()

            override fun getSession(): ConnectorSession = session

            override fun rewriteExpression(expression: ConnectorExpression): Optional<ParameterizedExpression> {
                assertThat(expression).isEqualTo(source)
                return Optional.of(ParameterizedExpression("coalesce(`dt`, ?)", parameters))
            }
        }
        for ((rule, expression, template) in cases) {
            val rewritten = rule.rewrite(table, expression, Captures.empty(), context).orElseThrow()
            assertThat(rewritten.expression).isEqualTo(template.format("coalesce(`dt`, ?)"))
            assertThat(rewritten.parameters).isEqualTo(parameters)
        }
    }

    private fun quote(name: String): String = "`${name.replace("`", "``")}`"
}
