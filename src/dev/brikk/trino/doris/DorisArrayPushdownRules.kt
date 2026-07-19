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

import io.airlift.log.Logger
import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.ArrayType
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.Type
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Typed, evidence-gated ARRAY predicate rules (PLAN §6.1 layer 2 / §6.2; evidence citations in
 * [DorisPushdownEvidence]). All rules fire in PREDICATE context only (WHERE conjuncts via
 * `DorisClient.convertPredicate`); anything outside the strict shape/type guards stays in
 * Trino. No boolean-composition rules ($not/$and/$or/$is_null) exist by design — that is a
 * CORRECTNESS invariant: `contains` is predicate-level (not value-level) equivalent, so its
 * rendering must never be embedded in a value context (see the NULL truth tables in the
 * evidence registry and TestDorisP2bPushdown pins).
 */
internal class DorisArrayPushdownSupport(
    private val typeMapping: DorisTypeMapping,
    private val quote: (String) -> String,
) {
    internal data class PushableArrayColumn(
        val quotedName: String,
        val elementType: Type,
        val elementTypeHandle: JdbcTypeHandle,
    )

    /**
     * Resolves a [Variable] to a pushable native Doris array column, or null (with a DEBUG
     * rejection log) when any guard fails. Pushable element types are the live-proven literal
     * families: integers, LARGEINT/DECIMAL (DECIMAL(38,0)-representable by construction —
     * PLAN §5 note), BOOLEAN, DATE, DATETIME. FLOAT/DOUBLE are excluded (approximate;
     * spike §7.5 forbids exact-equality pushdown over float elements), IPADDRESS is excluded
     * until its parameter rendering is live-proven (P2b open item), nested arrays excluded
     * (no element-literal shape exists).
     */
    fun pushableArrayColumn(context: RewriteContext<*>, variable: Variable): PushableArrayColumn? {
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle
            ?: return rejected("no JDBC column handle", variable.name)
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null)
            ?: return rejected("missing COLUMN_TYPE", variable.name)
        if (DorisColumnType.parse(columnType).baseName != "array") {
            return rejected("not a native Doris array column", variable.name)
        }
        val elementColumnType = DorisTypeMapping.arrayElementColumnType(columnType)
        val element = typeMapping.resolveArrayElement(elementColumnType)
            ?: return rejected("denied array element type", variable.name)
        if (!isPushableElement(element)) {
            return rejected("unsupported element type for pushdown: ${element.javaClass.simpleName}", variable.name)
        }
        if (variable.type != ArrayType(element.trinoType)) {
            return rejected("variable/element type mismatch", variable.name)
        }
        return PushableArrayColumn(
            quotedName = quote(column.columnName),
            elementType = element.trinoType,
            elementTypeHandle = DorisTypeMapping.toTypeHandle(elementColumnType),
        )
    }

    /** A needle must be a NON-NULL constant of exactly the element type (NULL semantics diverge; see evidence). */
    fun literalNeedle(expression: ConnectorExpression, elementType: Type): Optional<Any>? {
        if (expression !is Constant) {
            rejected("non-literal needle", expression.toString())
            return null
        }
        if (expression.type != elementType) {
            rejected("needle type ${expression.type} != element type $elementType", expression.toString())
            return null
        }
        val value = expression.value ?: run {
            rejected("NULL needle", expression.toString())
            return null
        }
        return Optional.of(value)
    }

    private fun rejected(reason: String, subject: String): PushableArrayColumn? {
        val category = reason.substringBefore(':')
        val count = REJECTION_COUNTS.computeIfAbsent(category) { LongAdder() }.also { it.increment() }.sum()
        log.debug("array pushdown rejected (%s; total for category=%s): %s", reason, count, subject)
        return null
    }

    companion object {
        private val log = Logger.get(DorisArrayPushdownSupport::class.java)

        /** PLAN §8 (minimal): rejection counts per reason category, DEBUG-logged. */
        private val REJECTION_COUNTS = ConcurrentHashMap<String, LongAdder>()

        internal fun isPushableElement(element: DorisArrayElement): Boolean = when (element) {
            is DorisArrayElement.BooleanElement,
            is DorisArrayElement.TinyintElement,
            is DorisArrayElement.SmallintElement,
            is DorisArrayElement.IntegerElement,
            is DorisArrayElement.BigintElement,
            is DorisArrayElement.LargeintElement,
            is DorisArrayElement.DecimalElement,
            is DorisArrayElement.DateElement,
            is DorisArrayElement.TimestampElement,
            -> true
            else -> false
        }
    }
}

/**
 * `contains(array(T), t)` -> `(array_contains(`col`, ?))` — evidence and NULL truth table in
 * [DorisPushdownEvidence.CONTAINS]. Rendered BARE (no `= 1` wrapper): profile-proven to be the
 * only form Doris accelerates with an ARRAY inverted index, and predicate-equivalent because
 * this connector emits it exclusively as a top-level WHERE conjunct.
 */
internal class RewriteContains(
    private val support: DorisArrayPushdownSupport,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val arrayArgument = expression.arguments[0] as? Variable ?: return Optional.empty()
        val column = support.pushableArrayColumn(context, arrayArgument) ?: return Optional.empty()
        val needle = support.literalNeedle(expression.arguments[1], column.elementType) ?: return Optional.empty()
        return Optional.of(
            ParameterizedExpression(
                "(array_contains(${column.quotedName}, ?))",
                listOf(QueryParameter(column.elementTypeHandle, column.elementType, needle)),
            ),
        )
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("contains")))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/**
 * `arrays_overlap(array(T), array(T))` -> `(arrays_overlap(array_filter(x -> x IS NOT NULL,
 * `l`), `r`))` — the null-strip wrapper is MANDATORY: without it Doris matches NULL elements
 * to each other and over-returns (evidence in [DorisPushdownEvidence.ARRAYS_OVERLAP]).
 * Column-to-column shape only; constant-array arguments are a P2b open item.
 */
internal class RewriteArraysOverlap(
    private val support: DorisArrayPushdownSupport,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val leftArgument = expression.arguments[0] as? Variable ?: return Optional.empty()
        val rightArgument = expression.arguments[1] as? Variable ?: return Optional.empty()
        val left = support.pushableArrayColumn(context, leftArgument) ?: return Optional.empty()
        val right = support.pushableArrayColumn(context, rightArgument) ?: return Optional.empty()
        if (left.elementType != right.elementType) {
            return Optional.empty()
        }
        return Optional.of(
            ParameterizedExpression(
                "(arrays_overlap(array_filter(x -> x IS NOT NULL, ${left.quotedName}), ${right.quotedName}))",
                listOf(),
            ),
        )
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("arrays_overlap")))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/**
 * `array_position(array(T), t) <cmp> n` (either orientation) -> `(array_position(`col`, ?)
 * <cmp> n)` — value-level equivalence proven for non-NULL needles (1-based, 0-if-absent,
 * NULL-array propagation identical; [DorisPushdownEvidence.ARRAY_POSITION]), so all six
 * comparison operators are exactly safe. The integer bound is rendered inline (a validated
 * Long — no injection surface).
 */
internal class RewriteArrayPositionComparison(
    private val support: DorisArrayPushdownSupport,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val leftArgument = expression.arguments[0]
        val rightArgument = expression.arguments[1]
        val operator = COMPARISON_OPERATORS.getValue(expression.functionName)
        val (positionCall, bound, effectiveOperator) = when {
            leftArgument is Call && rightArgument is Constant -> Triple(leftArgument, rightArgument, operator)
            leftArgument is Constant && rightArgument is Call -> Triple(rightArgument, leftArgument, flip(operator))
            else -> return Optional.empty()
        }
        return rewritePositionComparison(positionCall, bound, effectiveOperator, context)
    }

    private fun rewritePositionComparison(
        positionCall: Call,
        bound: Constant,
        operator: String,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<ParameterizedExpression> {
        if (positionCall.functionName != ARRAY_POSITION || positionCall.arguments.size != 2 || positionCall.type != BIGINT) {
            return Optional.empty()
        }
        if (bound.type != BIGINT && bound.type != INTEGER) {
            return Optional.empty()
        }
        val boundValue = bound.value as? Long ?: return Optional.empty()
        val arrayArgument = positionCall.arguments[0] as? Variable ?: return Optional.empty()
        val column = support.pushableArrayColumn(context, arrayArgument) ?: return Optional.empty()
        val needle = support.literalNeedle(positionCall.arguments[1], column.elementType) ?: return Optional.empty()
        return Optional.of(
            ParameterizedExpression(
                "(array_position(${column.quotedName}, ?) $operator $boundValue)",
                listOf(QueryParameter(column.elementTypeHandle, column.elementType, needle)),
            ),
        )
    }

    companion object {
        private val ARRAY_POSITION = FunctionName("array_position")

        private val COMPARISON_OPERATORS: Map<FunctionName, String> = mapOf(
            EQUAL_OPERATOR_FUNCTION_NAME to "=",
            NOT_EQUAL_OPERATOR_FUNCTION_NAME to "<>",
            LESS_THAN_OPERATOR_FUNCTION_NAME to "<",
            LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to "<=",
            GREATER_THAN_OPERATOR_FUNCTION_NAME to ">",
            GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to ">=",
        )

        /** Operator adjustment when the constant is on the LEFT (`n < array_position(...)`). */
        private fun flip(operator: String): String = when (operator) {
            "<" -> ">"
            "<=" -> ">="
            ">" -> "<"
            ">=" -> "<="
            else -> operator // = and <> are symmetric
        }

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in COMPARISON_OPERATORS.keys })
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}
