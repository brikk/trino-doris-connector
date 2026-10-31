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
import io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.Type
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
    fun testConditionalEvidenceNamesThisAdmissionProof() {
        val evidence = DorisPushdownEvidence.JSON_EXTRACT_SCALAR
        assertThat(evidence.treatment).isEqualTo(DorisPushdownEvidence.Treatment.GUARDED_ADMISSION)
        assertThat(evidence.admissionGuard).isEqualTo(DorisPushdownEvidence.AdmissionGuard.JSON_SCALAR_EQUALITY)
        assertThat(evidence.admissionGuard!!.unitProof).isEqualTo(javaClass.simpleName)
    }

    @Test
    fun testOnlyEqualityOverNativeJsonIsAdmitted() {
        for (reversed in listOf(false, true)) {
            assertThat(rewrite("$.a", reversed, comparison = NOT_EQUAL_OPERATOR_FUNCTION_NAME)).isEmpty
            assertThat(rewrite("$.a", reversed, inputType = VARCHAR)).isEmpty
            for (remoteType in listOf("variant", "string", "varchar(100)")) {
                assertThat(rewrite("$.a", reversed, remoteType = remoteType)).describedAs(remoteType).isEmpty
            }
            assertThat(rewrite("$.a", reversed, literal = "a\u0000b")).isEmpty
        }
    }

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

    @Test
    fun testRefreshedEvidencePathsRemainOutsideTheDottedFieldAllowlist() {
        // brikk's 484/4.1.5 capture: these paths differ by engine or exceed our
        // admission contract even when a particular input happens to agree.
        for (path in listOf("$", "$[-1]", "$[*]", "$.\"a.b\"", """$["a.b"]""", "$.a[0].b")) {
            for (reversed in listOf(false, true)) {
                assertThat(rewrite(path, reversed)).describedAs("$path reversed=$reversed").isEmpty
            }
        }
    }

    @Test
    fun testRefreshedEvidenceNumericAndContainerLiteralsStayLocal() {
        for (literal in listOf(
            "100.0", "100", "1.2300", "1.23", "1.0E-7", "1e-7", "1e-07",
            "-0.0", "-0", "0.00", "9007199254740993.1200", "9007199254740994",
            "inf", "-inf", "+inf", "Infinity", "-Infinity", "NaN", "nan",
            """{"b":1}""", "[1,2]",
        )) {
            for (reversed in listOf(false, true)) {
                assertThat(rewrite("$.a", reversed, literal)).describedAs("$literal reversed=$reversed").isEmpty
            }
        }
    }

    @Test
    fun testOrdinaryTextAndBooleanLiteralsRemainAdmitted() {
        // The JSON string "null" is distinct from a JSON null value, which both
        // scalar extractors turn into SQL NULL for the captured missing/null paths.
        for (literal in listOf("hello", "true", "false", "null", "", "中文", "emoji😀")) {
            for (reversed in listOf(false, true)) {
                val rewritten = rewrite("$.a", reversed, literal).orElseThrow()
                assertThat(rewritten.parameters().last()).isEqualTo(
                    QueryParameter(DorisTypeMapping.toTypeHandle("string"), VARCHAR, Optional.of(utf8Slice(literal))),
                )
            }
        }
    }

    private fun rewrite(
        path: String,
        reversed: Boolean,
        literal: String = "x",
        inputType: Type = JSON,
        remoteType: String = "json",
        comparison: FunctionName = EQUAL_OPERATOR_FUNCTION_NAME,
    ) = rewriter.rewrite(
        session,
        Call(
            BOOLEAN,
            comparison,
            listOf(
                Call(
                    VARCHAR,
                    FunctionName("json_extract_scalar"),
                    listOf(Variable("json_symbol", inputType), Constant(utf8Slice(path), VARCHAR)),
                ),
                Constant(utf8Slice(literal), VARCHAR),
            ).let { if (reversed) it.reversed() else it },
        ),
        mapOf("json_symbol" to JdbcColumnHandle("doc", DorisTypeMapping.toTypeHandle(remoteType), inputType)),
    )
}
