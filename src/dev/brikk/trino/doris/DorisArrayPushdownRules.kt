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
import io.trino.spi.expression.StandardFunctions.BETWEEN_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME
import io.airlift.slice.Slice
import io.trino.spi.expression.Variable
import io.trino.spi.block.Block
import io.trino.spi.type.ArrayType
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.Type
import io.trino.spi.type.TypeUtils.readNativeValue
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
        /** null when the element is not IPADDRESS; true=Doris IPV4, false=IPV6 (dialect rendering). */
        val ipV4: Boolean? = null,
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
            ipV4 = (element as? DorisArrayElement.IpAddressElement)?.isV4,
        )
    }

    /** A needle must be a NON-NULL constant of exactly the element type (NULL semantics diverge; see evidence). */
    fun literalNeedle(expression: ConnectorExpression, column: PushableArrayColumn): Optional<Any>? {
        if (expression !is Constant) {
            rejected("non-literal needle", expression.toString())
            return null
        }
        if (expression.type != column.elementType) {
            rejected("needle type ${expression.type} != element type ${column.elementType}", expression.toString())
            return null
        }
        val value = expression.value ?: run {
            rejected("NULL needle", expression.toString())
            return null
        }
        if (!ipNeedleRenderable(value, column.ipV4)) {
            rejected("IPADDRESS needle not renderable for a Doris IPV4 column (non-v4-mapped)", expression.toString())
            return null
        }
        return Optional.of(value)
    }

    /**
     * A Doris IPV4 column can only be compared against IPv4-mapped literals (a real IPv6 value
     * can never equal an IPV4 value); such needles are kept local. IPV6 and non-IP elements
     * accept any value.
     */
    private fun ipNeedleRenderable(value: Any, ipV4: Boolean?): Boolean {
        if (ipV4 != true) {
            return true
        }
        val slice = value as? Slice ?: return false
        return DorisTypeMapping.isV4MappedIpSlice(slice)
    }

    /**
     * A constant `ARRAY(T)` needle: returns its NON-NULL elements as bound query parameters
     * (the constant's type must be exactly `array(elementType)`), or null when the expression
     * is not such a constant array. NULL array elements are DROPPED: after the column side is
     * NULL-stripped by [RewriteArraysOverlap]'s guard, a NULL on the literal side can never
     * match a value (Doris only matches NULL-to-NULL), so dropping it is semantics-preserving
     * and removes the over-return hazard by construction. A whole-array NULL constant is never
     * pushable (Trino `arrays_overlap(x, NULL)` -> NULL -> row dropped; caller keeps it local).
     */
    fun literalArrayNonNullParameters(
        expression: ConnectorExpression,
        column: PushableArrayColumn,
    ): List<QueryParameter>? {
        if (expression !is Constant) {
            rejected("non-literal array needle", expression.toString())
            return null
        }
        if (expression.type != ArrayType(column.elementType)) {
            rejected("array needle type ${expression.type} != array(${column.elementType})", expression.toString())
            return null
        }
        val block = expression.value as? Block ?: run {
            rejected("NULL array needle", expression.toString())
            return null
        }
        val parameters = ArrayList<QueryParameter>(block.positionCount)
        for (i in 0 until block.positionCount) {
            if (block.isNull(i)) {
                continue
            }
            val value = readNativeValue(column.elementType, block, i)
            if (!ipNeedleRenderable(value, column.ipV4)) {
                // non-v4-mapped element against a Doris IPV4 column: can never match -> keep local
                rejected("IPADDRESS array element not renderable for a Doris IPV4 column", expression.toString())
                return null
            }
            parameters.add(QueryParameter(column.elementTypeHandle, column.elementType, Optional.of(value)))
        }
        return parameters
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
            is DorisArrayElement.IpAddressElement,
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
        val needle = support.literalNeedle(expression.arguments[1], column) ?: return Optional.empty()
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
 * `arrays_overlap(array(T), array(T))` in two shapes, both null-strip-GUARDED (the wrapper is
 * MANDATORY: without it Doris matches NULL elements to each other and over-returns — evidence
 * in [DorisPushdownEvidence.ARRAYS_OVERLAP]):
 *
 * - column × column -> `(arrays_overlap(array_filter(x -> x IS NOT NULL, `l`), `r`))`
 * - column × constant `ARRAY(T)` literal (either orientation; `arrays_overlap` is symmetric)
 *   -> `(arrays_overlap(array_filter(x -> x IS NOT NULL, `col`), ARRAY(?, ?, ...)))` where the
 *   bound parameters are the literal's NON-NULL elements ([DorisArrayPushdownSupport.literalArrayNonNullParameters]).
 *
 * An empty (or all-NULL) constant array stays LOCAL: the predicate can never qualify a row and
 * pushing an empty `ARRAY()` literal would need element-type inference we do not rely on. Still
 * correct (the engine evaluates it locally).
 */
internal class RewriteArraysOverlap(
    private val support: DorisArrayPushdownSupport,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val arg0 = expression.arguments[0]
        val arg1 = expression.arguments[1]
        if (arg0 is Variable && arg1 is Variable) {
            return rewriteColumnColumn(arg0, arg1, context)
        }
        // column × constant literal, either orientation (arrays_overlap is symmetric)
        val (variable, constant) = when {
            arg0 is Variable -> arg0 to arg1
            arg1 is Variable -> arg1 to arg0
            else -> return Optional.empty()
        }
        return rewriteColumnConstant(variable, constant, context)
    }

    private fun rewriteColumnColumn(
        leftArgument: Variable,
        rightArgument: Variable,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<ParameterizedExpression> {
        val left = support.pushableArrayColumn(context, leftArgument) ?: return Optional.empty()
        val right = support.pushableArrayColumn(context, rightArgument) ?: return Optional.empty()
        if (left.elementType != right.elementType || left.ipV4 != right.ipV4) {
            // ipV4 guard: Trino IPADDRESS erases v4/v6, but array<ipv4> vs array<ipv6> are
            // distinct Doris types — never overlap them remotely.
            return Optional.empty()
        }
        return Optional.of(
            ParameterizedExpression(
                "(arrays_overlap(array_filter(x -> x IS NOT NULL, ${left.quotedName}), ${right.quotedName}))",
                listOf(),
            ),
        )
    }

    private fun rewriteColumnConstant(
        variable: Variable,
        constant: ConnectorExpression,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<ParameterizedExpression> {
        val column = support.pushableArrayColumn(context, variable) ?: return Optional.empty()
        val parameters = support.literalArrayNonNullParameters(constant, column)
            ?: return Optional.empty()
        if (parameters.isEmpty()) {
            // empty / all-NULL constant array: never qualifies a row — keep it local (correct),
            // avoiding an untyped empty ARRAY() literal.
            return Optional.empty()
        }
        // IP literals bind as strings, and arrays_overlap (unlike array_contains) does NOT coerce
        // a varchar array to IPV4/IPV6 — it compares text, which diverges from Doris's canonical
        // form. An explicit per-element CAST forces the IP element type (live-proven). Numeric /
        // date / datetime params already bind as their native JDBC type, so they need no cast.
        val placeholder = when (column.ipV4) {
            true -> "CAST(? AS IPV4)"
            false -> "CAST(? AS IPV6)"
            null -> "?"
        }
        val placeholders = parameters.joinToString(", ") { placeholder }
        return Optional.of(
            ParameterizedExpression(
                "(arrays_overlap(array_filter(x -> x IS NOT NULL, ${column.quotedName}), ARRAY($placeholders)))",
                parameters,
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
        val needle = support.literalNeedle(positionCall.arguments[1], column) ?: return Optional.empty()
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

/**
 * `cardinality(array(T)) <cmp> n` (either orientation) and `cardinality(array(T)) BETWEEN
 * lo AND hi` -> `(array_size(`col`) ...)` — the registry verdict is DIVERGENT purely as a
 * RENAME (Doris has no `cardinality` for this shape; the portable name is `array_size`), so
 * per G3 this is a connector-original rewrite backed by its own live truth table
 * ([DorisPushdownEvidence.CARDINALITY]): NULL array -> NULL on both engines, empty -> 0,
 * NULL elements COUNTED on both, and NOT-composition cell-identical — VALUE-level identical,
 * so the rule lives in the VALUE-SAFE tier (composable under NOT/AND/OR) alongside
 * `array_position` comparisons. Bounds are validated Longs rendered inline (no injection
 * surface). Column guard reuses the pushable-array allowlist (FLOAT/DOUBLE/IP element
 * arrays are excluded — over-tight for a size check, but keeps one proof surface; noted in
 * NOTES-p5-batch).
 */
internal class RewriteCardinalityComparison(
    private val support: DorisArrayPushdownSupport,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.functionName == BETWEEN_FUNCTION_NAME) {
            val cardinalityCall = expression.arguments[0] as? Call ?: return Optional.empty()
            val column = pushableCardinalityColumn(cardinalityCall, context) ?: return Optional.empty()
            val low = integerBound(expression.arguments[1]) ?: return Optional.empty()
            val high = integerBound(expression.arguments[2]) ?: return Optional.empty()
            return Optional.of(ParameterizedExpression("(array_size(${column.quotedName}) BETWEEN $low AND $high)", listOf()))
        }
        val left = expression.arguments[0]
        val right = expression.arguments[1]
        val operator = COMPARISON_OPERATORS.getValue(expression.functionName)
        val (cardinalityCall, bound, effectiveOperator) = when {
            left is Call && right is Constant -> Triple(left, right, operator)
            left is Constant && right is Call -> Triple(right, left, flip(operator))
            else -> return Optional.empty()
        }
        val column = pushableCardinalityColumn(cardinalityCall, context) ?: return Optional.empty()
        val boundValue = integerBound(bound) ?: return Optional.empty()
        return Optional.of(ParameterizedExpression("(array_size(${column.quotedName}) $effectiveOperator $boundValue)", listOf()))
    }

    private fun pushableCardinalityColumn(
        call: Call,
        context: RewriteContext<ParameterizedExpression>,
    ): DorisArrayPushdownSupport.PushableArrayColumn? {
        if (call.functionName != CARDINALITY || call.arguments.size != 1 || call.type != BIGINT) {
            return null
        }
        val arrayArgument = call.arguments[0] as? Variable ?: return null
        return support.pushableArrayColumn(context, arrayArgument)
    }

    private fun integerBound(expression: ConnectorExpression): Long? {
        val constant = expression as? Constant ?: return null
        if (constant.type != BIGINT && constant.type != INTEGER) {
            return null
        }
        return constant.value as? Long
    }

    companion object {
        private val CARDINALITY = FunctionName("cardinality")

        private val COMPARISON_OPERATORS: Map<FunctionName, String> = mapOf(
            EQUAL_OPERATOR_FUNCTION_NAME to "=",
            NOT_EQUAL_OPERATOR_FUNCTION_NAME to "<>",
            LESS_THAN_OPERATOR_FUNCTION_NAME to "<",
            LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to "<=",
            GREATER_THAN_OPERATOR_FUNCTION_NAME to ">",
            GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to ">=",
        )

        private fun flip(operator: String): String = when (operator) {
            "<" -> ">"
            "<=" -> ">="
            ">" -> "<"
            ">=" -> "<="
            else -> operator // = and <> are symmetric
        }

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in COMPARISON_OPERATORS.keys || it == BETWEEN_FUNCTION_NAME })
            .with(type().equalTo(BOOLEAN))
    }
}
