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
import io.airlift.slice.Slices.utf8Slice
import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.Call
import io.trino.spi.expression.Constant
import io.trino.spi.expression.StandardFunctions.LIKE_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.VarcharType
import java.util.Optional

/**
 * `value LIKE pattern [ESCAPE escape]` pushdown — enabled ONLY in BINARY/FULL string-pushdown
 * mode. Probe evidence (`REPORT-string-comparison-probe-4.1.3.md`, LIKE section):
 * - Doris LIKE matching is byte/character semantics identical to Trino (case-sensitive,
 *   `%`/`_` identical, `_` is one CHARACTER incl. 4-byte emoji).
 * - Trino's NO-escape LIKE treats `\` as a LITERAL, while Doris's default escape IS `\` —
 *   the exact fix is doubling backslashes in the rendered pattern (probe-proven:
 *   Doris `'a\\b'` matches the literal `a\b`).
 * - With an explicit `ESCAPE 'x'`, Doris honors ONLY the declared escape char (backslash
 *   becomes literal — probed), matching Trino's ESCAPE contract, so the 3-arg form pushes
 *   with the pattern untouched.
 * - Patterns/escapes containing a NUL byte are never pushed (the probe's NUL under-return).
 *
 * Guards: the value must be a Variable over a native `varchar`/`string` Doris column (CHAR is
 * excluded — trailing-space stored data diverges, see the CHAR controller); the pattern (and
 * escape, if present) must be non-NULL varchar constants.
 */
internal class RewriteStringLike(
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun isEnabled(session: ConnectorSession): Boolean =
        DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown

    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.arguments.size !in 2..3) {
            return Optional.empty()
        }
        val column = pushableStringColumn(expression.arguments[0], context) ?: return Optional.empty()
        val pattern = varcharConstant(expression.arguments[1]) ?: return Optional.empty()
        val patternText = pattern.toStringUtf8()
        if (patternText.contains('\u0000')) {
            return Optional.empty()
        }
        val quotedColumn = quote(column.columnName)

        if (expression.arguments.size == 2) {
            // Trino no-escape semantics: backslash is literal -> double it for Doris's default escape.
            val dorisPattern = utf8Slice(patternText.replace("\\", "\\\\"))
            return Optional.of(
                ParameterizedExpression(
                    "($quotedColumn LIKE ?)",
                    listOf(QueryParameter(column.jdbcTypeHandle, VarcharType.VARCHAR, Optional.of(dorisPattern))),
                ),
            )
        }

        val escape = varcharConstant(expression.arguments[2]) ?: return Optional.empty()
        if (escape.toStringUtf8().contains('\u0000')) {
            return Optional.empty()
        }
        return Optional.of(
            ParameterizedExpression(
                "($quotedColumn LIKE ? ESCAPE ?)",
                listOf(
                    QueryParameter(column.jdbcTypeHandle, VarcharType.VARCHAR, Optional.of(pattern)),
                    QueryParameter(column.jdbcTypeHandle, VarcharType.VARCHAR, Optional.of(escape)),
                ),
            ),
        )
    }

    private fun pushableStringColumn(argument: io.trino.spi.expression.ConnectorExpression, context: RewriteContext<*>): JdbcColumnHandle? {
        val variable = argument as? Variable ?: return null
        if (variable.type !is VarcharType) {
            return null
        }
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle ?: return null
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null) ?: return null
        return when (DorisColumnType.parse(columnType).baseName) {
            "varchar", "string", "text" -> column
            else -> null // char excluded (trailing-space divergence); everything else is not a string column
        }
    }

    private fun varcharConstant(argument: io.trino.spi.expression.ConnectorExpression): Slice? {
        val constant = argument as? Constant ?: return null
        if (constant.type !is VarcharType) {
            return null
        }
        return constant.value as? Slice
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(LIKE_FUNCTION_NAME))
            .with(type().equalTo(BOOLEAN))
    }
}
