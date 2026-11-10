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
import io.trino.matching.Capture
import io.trino.matching.Capture.newCapture
import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.basicAggregation
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.distinct
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.functionName
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.hasFilter
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.hasSortOrder
import io.trino.plugin.base.aggregation.AggregateFunctionPatterns.singleArgument
import io.trino.plugin.base.aggregation.AggregateFunctionRule
import io.trino.plugin.base.aggregation.AggregateFunctionRule.RewriteContext
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.variable
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.AggregateFunction
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType
import io.trino.spi.type.DateType
import io.trino.spi.type.DecimalType
import io.trino.spi.type.Decimals.MAX_PRECISION
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.Type
import java.util.Optional

internal object DorisAggregatePushdown {
    private val log = Logger.get(DorisAggregatePushdown::class.java)

    /**
     * Types whose values compare, order and group identically in Doris and Trino. REAL and
     * DOUBLE are excluded: Trino orders NaN below every value while Doris returns NaN as the
     * max, and ±0.0 ties resolve differently. Grouping and TopN admit text keys separately.
     */
    internal fun isExactPushableKeyType(type: Type): Boolean = when (type) {
        is TinyintType,
        is SmallintType,
        is IntegerType,
        is BigintType,
        is DecimalType,
        is DateType,
        is TimestampType,
        is BooleanType,
        -> true
        else -> false
    }

    internal fun isExactOrNativeString(column: JdbcColumnHandle): Boolean =
        isExactPushableKeyType(column.columnType) || DorisColumnType.isNativeString(column)

    internal fun logRejection(reason: String, subject: Any) {
        log.debug("aggregate pushdown rejected (%s): %s", reason, subject)
    }
}

/** Doris min/max return the argument's type unchanged, so the result reuses the column's type handle. */
internal class DorisImplementMinMax : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = basicAggregation()
        .with(functionName().matching { it in MIN_MAX })
        .with(singleArgument().matching(variable().capturedAs(ARGUMENT)))

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val argument = captures.get(ARGUMENT)
        val columnHandle = context.getAssignment(argument.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (columnHandle.columnType != aggregateFunction.outputType) {
            DorisAggregatePushdown.logRejection(
                "unexpected min/max output type: ${aggregateFunction.outputType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactPushableKeyType(columnHandle.columnType)) {
            DorisAggregatePushdown.logRejection(
                "min/max argument type not exact-pushable (collation or ordering hazard): ${columnHandle.columnType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "${aggregateFunction.functionName}(${rewrittenArgument.expression()})",
                rewrittenArgument.parameters(),
                columnHandle.jdbcTypeHandle,
            ),
        )
    }

    companion object {
        private val ARGUMENT: Capture<Variable> = newCapture()
        private val MIN_MAX = setOf("min", "max")
    }
}

/**
 * Doris sums wrap silently on overflow (integers, LARGEINT and DECIMAL alike) where Trino fails,
 * so only DECIMAL with precision up to 18 is pushed: even 2^63 - 1 such rows stay below 10^37,
 * well short of the decimal(38) bound. REAL/DOUBLE sums depend on summation order.
 */
internal class DorisImplementSum : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = Pattern.typeOf(AggregateFunction::class.java)
        .with(hasSortOrder().equalTo(false))
        .with(hasFilter().equalTo(false))
        .with(functionName().equalTo("sum"))
        .with(singleArgument().matching(variable().capturedAs(ARGUMENT)))

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val argument = captures.get(ARGUMENT)
        val columnHandle = context.getAssignment(argument.name) as? JdbcColumnHandle ?: return Optional.empty()
        val argumentType = columnHandle.columnType
        if (argumentType !is DecimalType || argumentType.precision > MAX_SAFE_SUM_PRECISION) {
            DorisAggregatePushdown.logRejection(
                "sum argument type not overflow-safe (only DECIMAL(p<=$MAX_SAFE_SUM_PRECISION,s) is): $argumentType",
                aggregateFunction,
            )
            return Optional.empty()
        }
        // Doris sum(decimalv3(p, s)) returns decimalv3(38, s); any other Trino output type stays local.
        val outputType = aggregateFunction.outputType
        if (outputType !is DecimalType || outputType.precision != MAX_PRECISION || outputType.scale != argumentType.scale) {
            DorisAggregatePushdown.logRejection("unexpected sum output type shape: $outputType for $argumentType", aggregateFunction)
            return Optional.empty()
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElse(null) ?: return Optional.empty()
        val function = if (aggregateFunction.isDistinct) "sum(DISTINCT %s)" else "sum(%s)"
        return Optional.of(
            JdbcExpression(
                function.format(rewrittenArgument.expression()),
                rewrittenArgument.parameters(),
                DorisTypeMapping.toTypeHandle("decimalv3($MAX_PRECISION, ${argumentType.scale})"),
            ),
        )
    }

    companion object {
        private val ARGUMENT: Capture<Variable> = newCapture()

        internal const val MAX_SAFE_SUM_PRECISION = 18
    }
}

internal class DorisImplementCountDistinct : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = Pattern.typeOf(AggregateFunction::class.java)
        .with(distinct().equalTo(true))
        .with(hasFilter().equalTo(false))
        .with(functionName().equalTo("count"))
        .with(singleArgument().matching(variable().capturedAs(ARGUMENT)))

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val argument = captures.get(ARGUMENT)
        val columnHandle = context.getAssignment(argument.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (aggregateFunction.outputType != BIGINT) {
            DorisAggregatePushdown.logRejection(
                "unexpected count(DISTINCT) output type: ${aggregateFunction.outputType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactPushableKeyType(columnHandle.columnType)) {
            DorisAggregatePushdown.logRejection(
                "count(DISTINCT) argument type not exact-pushable (collation or distinctness hazard): ${columnHandle.columnType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "count(DISTINCT ${rewrittenArgument.expression()})",
                rewrittenArgument.parameters(),
                DorisTypeMapping.toTypeHandle("bigint"),
            ),
        )
    }

    companion object {
        private val ARGUMENT: Capture<Variable> = newCapture()
    }
}

internal class DorisImplementMinMaxBy : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = basicAggregation()
        .with(functionName().matching { it in MIN_MAX_BY })

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        if (aggregateFunction.arguments.size != 2) {
            return Optional.empty()
        }
        val value = aggregateFunction.arguments[0] as? Variable ?: return Optional.empty()
        val key = aggregateFunction.arguments[1] as? Variable ?: return Optional.empty()
        val valueColumn = context.getAssignment(value.name) as? JdbcColumnHandle ?: return Optional.empty()
        val keyColumn = context.getAssignment(key.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (valueColumn.columnType != aggregateFunction.outputType) {
            DorisAggregatePushdown.logRejection(
                "unexpected min_by/max_by output type: ${aggregateFunction.outputType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactPushableKeyType(keyColumn.columnType)) {
            DorisAggregatePushdown.logRejection(
                "min_by/max_by KEY type not exact-pushable: ${keyColumn.columnType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactOrNativeString(valueColumn)) {
            DorisAggregatePushdown.logRejection(
                "min_by/max_by VALUE type not pushable: ${valueColumn.columnType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        // Doris skips rows whose value is NULL; Trino returns the extreme key's value even when it
        // is NULL. Only a NOT NULL value column makes them agree.
        if (valueColumn.isNullable) {
            DorisAggregatePushdown.logRejection(
                "min_by/max_by VALUE column is NULLABLE (Doris skips NULL-value rows, Trino keeps them): ${valueColumn.columnName}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        val rewrittenValue = context.rewriteExpression(value).orElse(null) ?: return Optional.empty()
        val rewrittenKey = context.rewriteExpression(key).orElse(null) ?: return Optional.empty()
        val function = aggregateFunction.functionName
        return Optional.of(
            JdbcExpression(
                "$function(${rewrittenValue.expression()}, ${rewrittenKey.expression()})",
                listOf(),
                valueColumn.jdbcTypeHandle,
            ),
        )
    }

    companion object {
        private val MIN_MAX_BY = setOf("min_by", "max_by")
    }
}

internal class DorisImplementAnyValue : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = basicAggregation()
        .with(functionName().matching { it == "any_value" })
        .with(singleArgument().matching(variable().capturedAs(ANY_ARGUMENT)))

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val argument = captures.get(ANY_ARGUMENT)
        val column = context.getAssignment(argument.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (column.columnType != aggregateFunction.outputType) {
            DorisAggregatePushdown.logRejection(
                "unexpected any_value output type: ${aggregateFunction.outputType}",
                aggregateFunction,
            )
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactOrNativeString(column)) {
            DorisAggregatePushdown.logRejection("any_value argument type not pushable: ${column.columnType}", aggregateFunction)
            return Optional.empty()
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "any_value(${rewrittenArgument.expression()})",
                listOf(),
                column.jdbcTypeHandle,
            ),
        )
    }

    companion object {
        private val ANY_ARGUMENT: Capture<Variable> = newCapture()
    }
}

/** Opt-in: both engines estimate with HyperLogLog, but their sketches give different numbers. */
internal class DorisImplementApproxDistinct : AggregateFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<AggregateFunction> = basicAggregation()
        .with(functionName().matching { it == "approx_distinct" })
        .with(singleArgument().matching(variable().capturedAs(APPROX_ARGUMENT)))

    override fun rewrite(
        aggregateFunction: AggregateFunction,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        if (!DorisSessionProperties.isApproximatePushdownEnabled(context.session)) {
            DorisAggregatePushdown.logRejection("approximate pushdown disabled (doris.approximate-pushdown)", aggregateFunction)
            return Optional.empty()
        }
        val argument = captures.get(APPROX_ARGUMENT)
        val column = context.getAssignment(argument.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (aggregateFunction.outputType != BIGINT) {
            DorisAggregatePushdown.logRejection("approx_distinct output type: ${aggregateFunction.outputType}", aggregateFunction)
            return Optional.empty()
        }
        if (!DorisAggregatePushdown.isExactOrNativeString(column)) {
            DorisAggregatePushdown.logRejection("approx_distinct argument type not pushable: ${column.columnType}", aggregateFunction)
            return Optional.empty()
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElse(null) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "approx_count_distinct(${rewrittenArgument.expression()})",
                listOf(),
                DorisTypeMapping.toTypeHandle("bigint"),
            ),
        )
    }

    companion object {
        private val APPROX_ARGUMENT: Capture<Variable> = newCapture()
    }
}
