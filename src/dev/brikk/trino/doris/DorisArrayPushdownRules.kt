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

internal class DorisArrayPushdownSupport(
    private val typeMapping: DorisTypeMapping,
    private val quote: (String) -> String,
) {
    internal data class PushableArrayColumn(
        val quotedName: String,
        val elementType: Type,
        val elementTypeHandle: JdbcTypeHandle,
        /** Null for non-IP elements; otherwise true for a Doris IPV4 element, false for IPV6. */
        val ipV4: Boolean? = null,
    )

    fun pushableArrayColumn(context: RewriteContext<*>, variable: Variable): PushableArrayColumn? {
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle
        if (column == null) {
            logRejection("no JDBC column handle", variable.name)
            return null
        }
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null)
        if (columnType == null) {
            logRejection("missing COLUMN_TYPE", variable.name)
            return null
        }
        if (DorisColumnType.parse(columnType).baseName != "array") {
            logRejection("not a native Doris array column", variable.name)
            return null
        }
        val elementColumnType = DorisTypeMapping.arrayElementColumnType(columnType)
        val element = typeMapping.resolveArrayElement(elementColumnType)
        if (element == null) {
            logRejection("denied array element type", variable.name)
            return null
        }
        if (!isPushableElement(element)) {
            logRejection("unsupported element type for pushdown: ${element.javaClass.simpleName}", variable.name)
            return null
        }
        if (variable.type != ArrayType(element.trinoType)) {
            logRejection("variable/element type mismatch", variable.name)
            return null
        }
        return PushableArrayColumn(
            quotedName = quote(column.columnName),
            elementType = element.trinoType,
            elementTypeHandle = DorisTypeMapping.toTypeHandle(elementColumnType),
            ipV4 = (element as? DorisArrayElement.IpAddressElement)?.isV4,
        )
    }

    /** NULL needles stay local: the engines' NULL semantics differ for them. */
    fun literalNeedle(expression: ConnectorExpression, column: PushableArrayColumn): Any? {
        if (expression !is Constant) {
            logRejection("non-literal needle", expression.toString())
            return null
        }
        if (expression.type != column.elementType) {
            logRejection("needle type ${expression.type} != element type ${column.elementType}", expression.toString())
            return null
        }
        val value = expression.value
        if (value == null) {
            logRejection("NULL needle", expression.toString())
            return null
        }
        if (!DorisPushdownControllers.isWithinTemporalRange(column.elementType, value)) {
            logRejection("temporal needle outside Doris range", expression.toString())
            return null
        }
        if (!ipNeedleRenderable(value, column.ipV4)) {
            logRejection("IPADDRESS needle not renderable for a Doris IPV4 column (non-v4-mapped)", expression.toString())
            return null
        }
        return value
    }

    /** An IPv6 value can never equal an IPV4 element and has no IPV4 literal, so it stays local. */
    private fun ipNeedleRenderable(value: Any, ipV4: Boolean?): Boolean {
        if (ipV4 != true) {
            return true
        }
        val slice = value as? Slice ?: return false
        return DorisIpAddress.isV4Mapped(slice)
    }

    /**
     * The non-NULL elements of a constant array, as parameters. Dropping NULL elements is safe
     * because [RewriteArraysOverlap] strips NULLs from the column side, and Doris matches a NULL
     * element only to another NULL.
     */
    fun literalArrayNonNullParameters(
        expression: ConnectorExpression,
        column: PushableArrayColumn,
    ): List<QueryParameter>? {
        if (expression !is Constant) {
            logRejection("non-literal array needle", expression.toString())
            return null
        }
        if (expression.type != ArrayType(column.elementType)) {
            logRejection("array needle type ${expression.type} != array(${column.elementType})", expression.toString())
            return null
        }
        val block = expression.value as? Block
        if (block == null) {
            logRejection("NULL array needle", expression.toString())
            return null
        }
        val parameters = ArrayList<QueryParameter>(block.positionCount)
        for (i in 0 until block.positionCount) {
            if (block.isNull(i)) {
                continue
            }
            val value = readNativeValue(column.elementType, block, i)
            if (!DorisPushdownControllers.isWithinTemporalRange(column.elementType, value)) {
                logRejection("temporal array element outside Doris range", expression.toString())
                return null
            }
            if (!ipNeedleRenderable(value, column.ipV4)) {
                logRejection("IPADDRESS array element not renderable for a Doris IPV4 column", expression.toString())
                return null
            }
            parameters.add(QueryParameter(column.elementTypeHandle, column.elementType, Optional.of(value)))
        }
        return parameters
    }

    private fun logRejection(reason: String, subject: String) {
        log.debug("array pushdown rejected (%s): %s", reason, subject)
    }

    companion object {
        private val log = Logger.get(DorisArrayPushdownSupport::class.java)

        /** REAL/DOUBLE are excluded because these rules test exact equality; nested arrays have no literal form. */
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
 * When the needle is absent from an array holding NULL elements, Doris returns 0 where Trino
 * returns NULL. Both drop the row in a top-level WHERE conjunct, but under NOT Doris would keep
 * it, so this rule is never composed. It renders bare, without `= 1`, the only form Doris can
 * serve from an ARRAY inverted index.
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
                listOf(QueryParameter(column.elementTypeHandle, column.elementType, Optional.of(needle))),
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
 * The column side is wrapped in `array_filter(x -> x IS NOT NULL, ...)` because Doris treats NULL
 * elements as overlapping each other. Like `contains`, this is only a top-level WHERE conjunct.
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
            // Trino's IPADDRESS erases IPv4 vs IPv6, but Doris array<ipv4> and array<ipv6> differ.
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
            // An empty ARRAY() literal has no element type for Doris to infer.
            return Optional.empty()
        }
        // IP parameters bind as strings, and unlike array_contains, arrays_overlap compares a
        // string array as text instead of converting it to IPV4/IPV6.
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
 * For a non-NULL needle `array_position` returns the same value on both engines (1-based, 0 when
 * absent, NULL for a NULL array), so these comparisons may be composed under NOT/AND/OR.
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
            leftArgument is Constant && rightArgument is Call -> Triple(rightArgument, leftArgument, flipComparison(operator))
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
                listOf(QueryParameter(column.elementTypeHandle, column.elementType, Optional.of(needle))),
            ),
        )
    }

    companion object {
        private val ARRAY_POSITION = FunctionName("array_position")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in COMPARISON_OPERATORS.keys })
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/**
 * Doris spells `cardinality` as `array_size`, which returns the same value (NULL for a NULL
 * array, NULL elements counted), so these comparisons may be composed under NOT/AND/OR.
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
            left is Constant && right is Call -> Triple(right, left, flipComparison(operator))
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

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in COMPARISON_OPERATORS.keys || it == BETWEEN_FUNCTION_NAME })
            .with(type().equalTo(BOOLEAN))
    }
}
