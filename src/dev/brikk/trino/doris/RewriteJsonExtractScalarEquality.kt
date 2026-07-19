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
 * `json_extract_scalar(json_col, '$.path') = 'literal'` (either orientation) ->
 * `(json_unquote(json_extract(`col`, ?)) = ?)` — registry evidence in
 * [DorisPushdownEvidence.JSON_EXTRACT_SCALAR] (verdict IDENTICAL for the scalar rendering),
 * scope-restricted by the connector's own P5 live probe (`NOTES-p5-batch.md`) to the shapes
 * where the two engines are proven cell-identical THROUGH the connector:
 *
 * - EQUALITY ONLY. `<>`, `IS NULL`, and `IS NOT NULL` forms are NOT pushable: at a
 *   non-scalar path value (object/array) Trino returns SQL NULL while Doris returns the
 *   JSON text (`'{"b":1}'` / `'[1,2]'`) — a data-dependent divergence no literal guard can
 *   see. Under `=` with the literal guards below, both engines drop such rows identically.
 * - The PATH must be a constant varchar of "simple" shape (`$.key`, `$.a.b`, `$[0]`,
 *   `$.a[0].b`, ...): dotted/quoted-key syntaxes differ between the engines
 *   (Trino `$["a.b"]` vs Doris `$."a.b"`) and stay local.
 * - The LITERAL must not start with `{`/`[` (could equal Doris's non-scalar text where
 *   Trino sees NULL — over-return), must not look like a JSON number (both engines
 *   canonicalize number text INDEPENDENTLY and diverge: Doris `1e+30`/`1.5e-07`/`-0` vs
 *   Trino `1E+30`/`1.5E-7`/`0` — under-return through a pushed filter), and must not
 *   contain NUL (P4 policy). `'true'`/`'false'`/`'null'` are proven identical and push.
 *
 * JSON-null-at-path and missing-key both yield SQL NULL on BOTH engines (live-proven —
 * Doris `json_unquote(json_extract(...))` returns SQL NULL for JSON null, unlike MySQL's
 * `'null'` text), so `=` needs no null-shape guard. Predicate-level tier: top-level WHERE
 * conjuncts only, never composed (a remote `NOT` would flip the both-drop divergent cells).
 */
internal class RewriteJsonExtractScalarEquality(
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val left = expression.arguments[0]
        val right = expression.arguments[1]
        // '=' is symmetric — accept both orientations
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

    /** A Variable over a native Doris JSON column (VARIANT is excluded until separately proven). */
    private fun pushableJsonColumn(argument: ConnectorExpression, context: RewriteContext<*>): JdbcColumnHandle? {
        val variable = argument as? Variable ?: return null
        if (variable.type.baseName != StandardTypes.JSON) {
            return null
        }
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle ?: return null
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null) ?: return null
        return if (DorisColumnType.parse(columnType).baseName == "json") column else null
    }

    /** Constant varchar path of live-proven identically-interpreted shape. */
    private fun simplePath(argument: ConnectorExpression): Slice? {
        val constant = argument as? Constant ?: return null
        if (constant.type !is VarcharType) {
            return null
        }
        val slice = constant.value as? Slice ?: return null
        return if (SIMPLE_PATH.matches(slice.toStringUtf8())) slice else null
    }

    /** Non-NULL varchar literal that cannot collide with a probe-flagged divergent cell. */
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
        if (NUMERIC_LOOKING.matches(text)) {
            return null // number canonicalization diverges (1e+30 vs 1E+30, -0 vs 0, ...)
        }
        return slice
    }

    companion object {
        private val JSON_EXTRACT_SCALAR = FunctionName("json_extract_scalar")

        /** `$.key`, `$.a.b`, `$[0]`, `$.a[0].b`, ... — the syntax subset both engines parse identically. */
        private val SIMPLE_PATH = Regex("""\$(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+])+""")

        /** Broad "could be a number rendering on either engine" test — anything matching stays local. */
        private val NUMERIC_LOOKING = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

        private val VARCHAR_TYPE_HANDLE = DorisTypeMapping.toTypeHandle("string")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(EQUAL_OPERATOR_FUNCTION_NAME))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}
