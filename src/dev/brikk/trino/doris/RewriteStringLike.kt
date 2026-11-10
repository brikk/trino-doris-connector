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
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.StandardFunctions.LIKE_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.VarcharType
import java.util.Optional

/** Doris LIKE is case-sensitive and matches `_` to one character, even a 4-byte one, as Trino does. */
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
            // Without ESCAPE, Trino treats a backslash literally but Doris uses it as the escape.
            val dorisPattern = utf8Slice(patternText.replace("\\", "\\\\"))
            return Optional.of(
                ParameterizedExpression(
                    "($quotedColumn LIKE ?)",
                    listOf(QueryParameter(column.jdbcTypeHandle, VarcharType.VARCHAR, Optional.of(dorisPattern))),
                ),
            )
        }

        val escape = varcharConstant(expression.arguments[2]) ?: return Optional.empty()
        val escapeText = escape.toStringUtf8()
        // Doris honours only the declared escape, as Trino does, but rejects non-ASCII escapes.
        if (escapeText.length != 1 || escapeText[0] !in '\u0001'..'\u007f') {
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

    private fun pushableStringColumn(argument: ConnectorExpression, context: RewriteContext<*>): JdbcColumnHandle? {
        val variable = argument as? Variable ?: return null
        if (variable.type !is VarcharType) {
            return null
        }
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle ?: return null
        // CHAR excluded (trailing spaces)
        return column.takeIf(DorisColumnType::isNativeString)
    }

    private fun varcharConstant(argument: ConnectorExpression): Slice? {
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
