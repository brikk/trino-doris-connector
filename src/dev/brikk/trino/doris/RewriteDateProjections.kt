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
import io.trino.plugin.base.projection.ProjectFunctionRule
import io.trino.plugin.base.projection.ProjectFunctionRule.RewriteContext
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.ConnectorTableHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.CAST_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimestampType
import io.trino.spi.type.VarcharType
import java.util.Optional

/** Trino's `date(x)` is this cast, so the rule covers both spellings. */
internal class RewriteCastDatetimeToDate : ProjectFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = PATTERN

    override fun rewrite(
        handle: ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val call = expression as Call
        val source = pushableDatetimeColumn(call.arguments[0]) ?: return Optional.empty()
        val rewritten = context.rewriteExpression(source).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "CAST(${rewritten.expression()} AS DATE)",
                rewritten.parameters(),
                DorisTypeMapping.toTypeHandle("date"),
            ),
        )
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(CAST_FUNCTION_NAME))
            .with(type().equalTo(DATE))
            .with(argumentCount().equalTo(1))
    }
}

/**
 * Doris takes the arguments in the opposite order. `week` is not pushed: for 0000-01-01 and
 * 0000-01-02 Trino truncates into year -1 while Doris clamps to 0000-01-01, which no literal
 * check can detect. Doris rejects `millisecond`.
 */
internal class RewriteDateTrunc : ProjectFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = PATTERN

    override fun rewrite(
        handle: ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val call = expression as Call
        val unit = constantUnit(call.arguments[0]) ?: return Optional.empty()
        val source = pushableDatetimeColumn(call.arguments[1]) ?: return Optional.empty()
        val resultType = call.type as? TimestampType ?: return Optional.empty()
        if (resultType.precision > DorisTypeMapping.MAX_DATETIME_PRECISION) {
            return Optional.empty()
        }
        val rewritten = context.rewriteExpression(source).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "date_trunc(${rewritten.expression()}, '$unit')",
                rewritten.parameters(),
                DorisTypeMapping.toTypeHandle("datetime(${resultType.precision})"),
            ),
        )
    }

    private fun constantUnit(argument: ConnectorExpression): String? {
        val constant = argument as? Constant ?: return null
        if (constant.type !is VarcharType) {
            return null
        }
        val text = (constant.value as? Slice)?.toStringUtf8() ?: return null
        return text.takeIf { it in PROVEN_UNITS }
    }

    companion object {
        private val PROVEN_UNITS = setOf("second", "minute", "hour", "day", "month", "quarter", "year")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("date_trunc")))
            .with(argumentCount().equalTo(2))
    }
}

/** TIMESTAMP column shape; the scalar rewriter resolves its physical name and validates native metadata. */
private fun pushableDatetimeColumn(argument: ConnectorExpression): Variable? {
    val variable = argument as? Variable ?: return null
    val type = variable.type as? TimestampType ?: return null
    return variable.takeIf { type.precision <= DorisTypeMapping.MAX_DATETIME_PRECISION }
}
