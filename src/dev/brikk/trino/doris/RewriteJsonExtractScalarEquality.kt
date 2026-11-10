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

import io.airlift.slice.Slice
import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.StandardTypes
import io.trino.spi.type.VarcharType
import io.trino.spi.type.VarcharType.VARCHAR
import java.util.Optional

/**
 * Pushed only where the engines agree:
 *
 * - Equality only, as a top-level WHERE conjunct. For an object or array at the path, Trino
 *   returns NULL while Doris returns the JSON text; with the literal checks below `=` drops
 *   those rows on both engines, but `<>`, IS NULL or NOT would not.
 * - Plain dotted field paths only: Doris treats `[0]` on a non-array as identity, Trino can read
 *   numeric tokens as object keys, and the quoted-key syntaxes differ.
 * - Literals stay local if they start with `{` or `[`, look like a number (the engines format
 *   numbers differently, e.g. `1e+30` and `1.0E30`), spell a non-finite number (Doris can
 *   extract `inf` from overflowed JSON) or contain 0x00.
 *
 * A JSON null and a missing key both yield SQL NULL on both engines.
 */
internal class RewriteJsonExtractScalarEquality(
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val left = expression.arguments[0]
        val right = expression.arguments[1]
        val (extractCall, literal) = when {
            left is Call && right is Constant -> left to right
            left is Constant && right is Call -> right to left
            else -> return Optional.empty()
        }
        if (extractCall.functionName != JSON_EXTRACT_SCALAR || extractCall.arguments.size != 2) {
            return Optional.empty()
        }
        if (extractCall.type !is VarcharType) {
            return Optional.empty()
        }
        val column = pushableJsonColumn(extractCall.arguments[0], context) ?: return Optional.empty()
        val path = simplePath(extractCall.arguments[1]) ?: return Optional.empty()
        val literalSlice = safeLiteral(literal) ?: return Optional.empty()
        return Optional.of(
            ParameterizedExpression(
                "(json_unquote(json_extract(${quote(column.columnName)}, ?)) = ?)",
                listOf(
                    QueryParameter(VARCHAR_TYPE_HANDLE, VARCHAR, Optional.of(path)),
                    QueryParameter(VARCHAR_TYPE_HANDLE, VARCHAR, Optional.of(literalSlice)),
                ),
            ),
        )
    }

    /** Native Doris JSON only; VARIANT, which also maps to Trino JSON, is not admitted. */
    private fun pushableJsonColumn(argument: ConnectorExpression, context: RewriteContext<*>): JdbcColumnHandle? {
        val variable = argument as? Variable ?: return null
        if (variable.type.baseName != StandardTypes.JSON) {
            return null
        }
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle ?: return null
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null) ?: return null
        return if (DorisColumnType.parse(columnType).baseName == "json") column else null
    }

    private fun simplePath(argument: ConnectorExpression): Slice? {
        val constant = argument as? Constant ?: return null
        if (constant.type !is VarcharType) {
            return null
        }
        val slice = constant.value as? Slice ?: return null
        return if (SIMPLE_PATH.matches(slice.toStringUtf8())) slice else null
    }

    private fun safeLiteral(constant: Constant): Slice? {
        if (constant.type !is VarcharType) {
            return null
        }
        val slice = constant.value as? Slice ?: return null
        val text = slice.toStringUtf8()
        if (text.contains('\u0000')) {
            return null
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            return null // could equal Doris's non-scalar JSON text where Trino sees NULL
        }
        if (NUMERIC_LOOKING.matches(text) || NON_FINITE_LOOKING.matches(text)) {
            return null // number canonicalization diverges (1e+30 vs 1.0E30, -0 vs 0, ...)
        }
        return slice
    }

    companion object {
        private val JSON_EXTRACT_SCALAR = FunctionName("json_extract_scalar")

        private val SIMPLE_PATH = Regex("""\$(\.[A-Za-z_][A-Za-z0-9_]*)+""")

        /** Deliberately broad: anything either engine might print as a number stays local. */
        private val NUMERIC_LOOKING = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

        private val NON_FINITE_LOOKING = Regex("""[+-]?(inf(inity)?|nan)""", RegexOption.IGNORE_CASE)

        private val VARCHAR_TYPE_HANDLE = DorisTypeMapping.toTypeHandle("string")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(EQUAL_OPERATOR_FUNCTION_NAME))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}
