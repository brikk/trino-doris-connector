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
package dev.brikk.doris.trino.plugin

import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.AND_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.OR_FUNCTION_NAME
import io.trino.spi.type.BooleanType.BOOLEAN
import java.util.Optional

/**
 * Boolean composition (`NOT` / `$and` / `$or`) over the VALUE-SAFE rule tier ONLY.
 *
 * The safety proof has two independently live-proven halves (2026-07-19, Doris 4.1.3; pinned
 * in `TestDorisP3Composition`):
 * 1. The value-safe operands — `array_position` comparisons — return IDENTICAL values on both
 *    engines for every reachable shape, including NULL propagation
 *    ([DorisPushdownEvidence.ARRAY_POSITION]).
 * 2. Doris NOT/AND/OR follow exactly Trino's SQL three-valued logic (all cells probed:
 *    NOT NULL=NULL; TRUE AND NULL=NULL, FALSE AND NULL=FALSE; TRUE OR NULL=TRUE,
 *    FALSE OR NULL=NULL, NULL OR NULL=NULL).
 * A deterministic composition of identical operand values under identical connectives is
 * itself value-identical — so composed forms remain safe even under further composition.
 *
 * `contains`/`arrays_overlap` are structurally EXCLUDED from this tier: they are only
 * predicate-level equivalent (their not-found-with-NULL-elements cells differ: Trino NULL vs
 * Doris 0/1), so `NOT contains(...)` composed remotely would over-return (counterfactual
 * proven in `TestDorisP2bPushdown.testNotContainsSemanticsPreserved`). The composition rules
 * therefore rewrite children through [valueSafeRewriter] — a rewriter that simply does not
 * contain the predicate-level rules — never through the enclosing rewriter's defaultRewrite.
 */
internal class DorisValueSafeRewriter {
    lateinit var rewriter: ConnectorExpressionRewriter<ParameterizedExpression>

    fun rewrite(context: RewriteContext<ParameterizedExpression>, expression: ConnectorExpression): Optional<ParameterizedExpression> =
        rewriter.rewrite(context.session, expression, context.assignments)
}

/** `$not(value-safe)` -> `(NOT <child>)`. */
internal class RewriteValueSafeNot(
    private val valueSafeRewriter: DorisValueSafeRewriter,
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

/** `$and(value-safe...)` / `$or(value-safe...)` (variadic) -> `(<child> AND/OR <child> ...)`. */
internal class RewriteValueSafeLogical(
    private val valueSafeRewriter: DorisValueSafeRewriter,
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
                // any non-value-safe child keeps the WHOLE composition in Trino
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
