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
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.spi.expression.Call
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import io.trino.type.JsonType.JSON
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class TestDorisJsonExtractScalarEquality {
    private val session = TestingConnectorSession.builder().build()
    private val rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
        .add(RewriteJsonExtractScalarEquality { "`$it`" })
        .build()

    @Test
    fun testDottedPathsPreserveRenderingAndParameters() {
        for (path in listOf("$.a", "$.a.b", "$._key.A_Z9")) {
            for (reversed in listOf(false, true)) {
                val rewritten = rewrite(path, reversed).orElseThrow()
                assertThat(rewritten.expression()).isEqualTo("(json_unquote(json_extract(`doc`, ?)) = ?)")
                assertThat(rewritten.parameters()).containsExactly(
                    QueryParameter(DorisTypeMapping.toTypeHandle("string"), VARCHAR, Optional.of(utf8Slice(path))),
                    QueryParameter(DorisTypeMapping.toTypeHandle("string"), VARCHAR, Optional.of(utf8Slice("x"))),
                )
            }
        }
    }

    @Test
    fun testSubscriptPathsAreRejectedInEitherOrientation() {
        for (path in listOf("$[0]", "$[1]", "$.a[0]", "$.a[1]", "$.a[0].b", "$.a.b[12]", "$.a[0][1]")) {
            for (reversed in listOf(false, true)) {
                assertThat(rewrite(path, reversed)).describedAs("$path reversed=$reversed").isEmpty
            }
        }
    }

    private fun rewrite(path: String, reversed: Boolean) = rewriter.rewrite(
        session,
        Call(
            BOOLEAN,
            EQUAL_OPERATOR_FUNCTION_NAME,
            listOf(
                Call(
                    VARCHAR,
                    FunctionName("json_extract_scalar"),
                    listOf(Variable("json_symbol", JSON), Constant(utf8Slice(path), VARCHAR)),
                ),
                Constant(utf8Slice("x"), VARCHAR),
            ).let { if (reversed) it.reversed() else it },
        ),
        mapOf("json_symbol" to JdbcColumnHandle("doc", DorisTypeMapping.toTypeHandle("json"), JSON)),
    )
}
