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

import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.Call
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.AND_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.OR_FUNCTION_NAME
import io.trino.spi.type.BooleanType.BOOLEAN
import java.util.Optional

/**
 * NOT/AND/OR over value-safe operands only. Doris follows Trino's three-valued logic, so
 * composing operands that return identical values yields identical values. Children are
 * rewritten through [valueSafeRewriter], not the enclosing rewriter, so predicate-level rules
 * such as `contains`, whose NULL results differ, can never end up under NOT.
 */
internal class RewriteValueSafeNot(
    private val valueSafeRewriter: DorisRewriterHolder,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        return valueSafeRewriter.rewrite(context, expression.arguments[0])
            .map { child -> ParameterizedExpression("(NOT ${child.expression()})", child.parameters()) }
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(NOT_FUNCTION_NAME))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(1))
    }
}

/** See [RewriteValueSafeNot]. */
internal class RewriteValueSafeLogical(
    private val valueSafeRewriter: DorisRewriterHolder,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.arguments.size < 2) {
            return Optional.empty()
        }
        val operator = OPERATORS.getValue(expression.functionName)
        val children = ArrayList<ParameterizedExpression>(expression.arguments.size)
        for (argument in expression.arguments) {
            val child = valueSafeRewriter.rewrite(context, argument)
            if (child.isEmpty) {
                return Optional.empty()
            }
            children.add(child.get())
        }
        val sql = children.joinToString(separator = " $operator ", prefix = "(", postfix = ")") { it.expression() }
        val parameters = ArrayList<QueryParameter>()
        children.forEach { parameters.addAll(it.parameters()) }
        return Optional.of(ParameterizedExpression(sql, parameters))
    }

    companion object {
        private val OPERATORS: Map<FunctionName, String> = mapOf(
            AND_FUNCTION_NAME to "AND",
            OR_FUNCTION_NAME to "OR",
        )

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in OPERATORS.keys })
            .with(type().equalTo(BOOLEAN))
    }
}
