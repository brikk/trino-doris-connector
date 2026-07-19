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

import com.google.common.base.Verify.verify
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
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DecimalType
import io.trino.spi.type.Type
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Verified aggregate pushdown (PLAN §6.5, P4): shared type gate and rejection observability
 * for the aggregate rules below. Every family here is gated on a LIVE Doris 4.1.3 semantic
 * proof recorded in `dev-docs/NOTES-p4-aggregates.md` and pinned by `TestDorisP4AggregateProbes`.
 * These are standard SQL aggregates (COUNT/MIN/MAX/SUM), not brikk hazard-registry surface —
 * the evidence lives in the per-rule KDoc and the probe suite, not in [DorisPushdownEvidence].
 */
internal object DorisAggregatePushdown {
    private val log = Logger.get(DorisAggregatePushdown::class.java)

    /** PLAN §8 (minimal): aggregate-pushdown rejection counts per reason category, DEBUG-logged. */
    private val REJECTION_COUNTS = ConcurrentHashMap<String, LongAdder>()

    /**
     * Types whose value, ordering, and grouping semantics are live-proven identical between
     * Trino and Doris — the ONLY types allowed as min/max arguments and GROUP BY keys (and,
     * for [DorisImplementCountDistinct], as distinct-count arguments). Deliberately the same
     * set as the safe-TopN sort keys ([DorisClient.supportsTopN]): ordering identity is
     * exactly what min/max relies on. Excluded with evidence:
     * - CHAR/VARCHAR: Doris collation unproven (G5; same reason as string TopN/predicates).
     * - REAL/DOUBLE: DIVERGENT min/max — Trino 483 `max` uses COMPARISON_UNORDERED_FIRST
     *   (NaN smallest -> `max(NaN, Infinity) = Infinity`) while Doris 4.1.3 returns NaN as
     *   the max (live probe 2026-07-19: `max({NaN, 1.5, Infinity, -0.0, 0.0}) = NaN`), and
     *   ±0.0 tie-breaks are order-dependent on both engines (textually distinguishable).
     *   Grouping over NaN/-0.0 is equally unproven.
     * - JSON/IPADDRESS/ARRAY and everything else: no ordering/grouping identity evidence.
     */
    internal fun isExactPushableKeyType(type: Type): Boolean = when (type) {
        is io.trino.spi.type.TinyintType,
        is io.trino.spi.type.SmallintType,
        is io.trino.spi.type.IntegerType,
        is io.trino.spi.type.BigintType,
        is io.trino.spi.type.DecimalType,
        is io.trino.spi.type.DateType,
        is io.trino.spi.type.TimestampType,
        is io.trino.spi.type.BooleanType,
        -> true
        else -> false
    }

    internal fun rejected(reason: String, subject: Any): Optional<JdbcExpression> {
        val category = reason.substringBefore(':')
        val count = REJECTION_COUNTS.computeIfAbsent(category) { LongAdder() }.also { it.increment() }.sum()
        log.debug("aggregate pushdown rejected (%s; total for category=%s): %s", reason, count, subject)
        return Optional.empty()
    }
}

/**
 * `min(x)` / `max(x)` over the exact-pushable key types only
 * ([DorisAggregatePushdown.isExactPushableKeyType] carries the exclusion evidence).
 *
 * Live proof (2026-07-19, `p4_agg` probes + `TestDorisP4Aggregates` differentials): Doris
 * min/max return the argument type unchanged for every allowed family — BOOLEAN surfaces as
 * `tinyint(1)`, LARGEINT as `largeint`, DATETIME(p) as `datetime(p)` (probed via view
 * COLUMN_TYPE) — so reusing the argument column's type handle round-trips the result through
 * the exact same proven read path as a plain column scan (LARGEINT text-parse, DATETIME
 * LocalDateTime, DATE text). NULL semantics identical: min/max over an empty table or an
 * all-NULL group is NULL on both engines (probed).
 */
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
        val columnHandle = context.getAssignment(argument.name) as JdbcColumnHandle
        verify(columnHandle.columnType == aggregateFunction.outputType)
        if (!DorisAggregatePushdown.isExactPushableKeyType(columnHandle.columnType)) {
            return DorisAggregatePushdown.rejected(
                "min/max argument type not exact-pushable (collation or ordering hazard): ${columnHandle.columnType}",
                aggregateFunction,
            )
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElseThrow()
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
 * `sum([DISTINCT] x)` for DECIMAL(p <= 18, s) arguments ONLY — the single sum family whose
 * overflow surface is proven unreachable. Live proofs (2026-07-19, `p4_agg`):
 *
 * - Doris `sum(DECIMALV3(p, s))` returns `decimalv3(38, s)` (view COLUMN_TYPE probe), exactly
 *   Trino's `sum(decimal(p, s)) -> decimal(38, s)`, so the synthesized result handle
 *   round-trips scale exactly through the standard decimal read path.
 * - Doris decimal sum overflow is SILENT Int128 WRAPAROUND, not an error, despite
 *   `check_overflow_for_decimal=true`: `sum` of 2 x DECIMALV3(38,2)-max returned
 *   `-1402823669209384634633746074317682114.5` (+ a NUL byte) where Trino throws "Decimal
 *   overflow". Pushing is therefore only safe where wrap is UNREACHABLE: with p <= 18 the
 *   accumulated magnitude after even 2^63-1 rows (the engine-wide row-count ceiling) is
 *   < 10^18 * 9.3*10^18 < 10^37 — an order of magnitude below both the 10^38 decimal(38)
 *   boundary and the 2^127 Int128 wrap. For p >= 19 the boundary is theoretically reachable,
 *   so those stay local (Trino then enforces its own overflow error).
 * - This gate structurally excludes LARGEINT (mapped DECIMAL(38,0)): Doris `sum(LARGEINT)`
 *   silently wraps at 2^128 (live probe: sum of 4 x 2^126 returned 0).
 * - `sum(BIGINT)` is NOT implemented by any rule: Doris wraps silently
 *   (`sum(MAX_BIGINT, 1) = -9223372036854775808` live) where Trino throws. Narrower integer
 *   sums (TINYINT..INTEGER) output BIGINT remotely and share the same wrap hazard.
 * - `sum(REAL/DOUBLE)`: approximate types; distributed summation order makes results
 *   engine- and run-dependent. Never pushed.
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
        val columnHandle = context.getAssignment(argument.name) as JdbcColumnHandle
        val argumentType = columnHandle.columnType
        if (argumentType !is DecimalType || argumentType.precision > MAX_SAFE_SUM_PRECISION) {
            return DorisAggregatePushdown.rejected(
                "sum argument type not overflow-safe (only DECIMAL(p<=$MAX_SAFE_SUM_PRECISION,s) is): $argumentType",
                aggregateFunction,
            )
        }
        // Trino's sum(decimal(p, s)) output type is decimal(38, s) == Doris's sum(decimalv3(p, s))
        // return type (live-probed). Anything else is an engine-shape drift: stay local.
        val outputType = aggregateFunction.outputType
        if (outputType !is DecimalType || outputType.precision != SUM_RESULT_PRECISION || outputType.scale != argumentType.scale) {
            return DorisAggregatePushdown.rejected("unexpected sum output type shape: $outputType for $argumentType", aggregateFunction)
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElseThrow()
        val function = if (aggregateFunction.isDistinct) "sum(DISTINCT %s)" else "sum(%s)"
        return Optional.of(
            JdbcExpression(
                function.format(rewrittenArgument.expression()),
                rewrittenArgument.parameters(),
                DorisTypeMapping.toTypeHandle("decimalv3($SUM_RESULT_PRECISION, ${argumentType.scale})"),
            ),
        )
    }

    companion object {
        private val ARGUMENT: Capture<Variable> = newCapture()

        /**
         * 10^(38-18) = 10^20 max-magnitude addends are needed to reach the decimal(38) sum
         * boundary — more than 10x the 2^63-1 engine row-count ceiling. p=19 would need
         * 10^19 > 2^63-1 rows too, but 18 keeps a full order-of-magnitude margin AND aligns
         * with Trino's short-decimal boundary.
         */
        internal const val MAX_SAFE_SUM_PRECISION = 18

        private const val SUM_RESULT_PRECISION = 38
    }
}

/**
 * `count(DISTINCT x)` over exact-pushable key types only: distinctness is an equality/grouping
 * question, so the same identity evidence as GROUP BY keys applies (and the same exclusions:
 * no text — collation/case; no REAL/DOUBLE — NaN/±0.0 distinctness unproven). Result is
 * BIGINT on both engines (view COLUMN_TYPE probe). NULLs are ignored by count(DISTINCT) on
 * both engines (live differential incl. all-NULL and empty inputs).
 *
 * Plain `count(*)` / `count(x)` use the stock base-jdbc rules ([io.trino.plugin.jdbc.aggregation.ImplementCountAll],
 * [io.trino.plugin.jdbc.aggregation.ImplementCount]): counting non-NULLs has no type hazard,
 * and the live proofs (empty table -> 0, all-NULL group -> 0) are pinned in the P4 suites.
 */
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
        val columnHandle = context.getAssignment(argument.name) as JdbcColumnHandle
        verify(aggregateFunction.outputType == BIGINT)
        if (!DorisAggregatePushdown.isExactPushableKeyType(columnHandle.columnType)) {
            return DorisAggregatePushdown.rejected(
                "count(DISTINCT) argument type not exact-pushable (collation or distinctness hazard): ${columnHandle.columnType}",
                aggregateFunction,
            )
        }
        val rewrittenArgument = context.rewriteExpression(argument).orElseThrow()
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

/**
 * `min_by(a, b)` / `max_by(a, b)` -> same-named Doris aggregates (READ-ONLY-MAX batch 2).
 *
 * Live truth table (2026-07-19, `b2_doris` probes + `TestDorisB2Aggregates` differentials):
 * - rows whose KEY (b) is NULL are IGNORED on both engines; all-NULL-key/empty groups
 *   return NULL on both;
 * - **NULL-VALUE divergence**: for a group holding {(a=NULL, b=1), (a=60, b=2)} Doris
 *   min_by SKIPS the NULL-value row (returns 60) while Trino keeps the payload of the
 *   minimal key (returns NULL). The divergence lives in DATA -> the rule requires the
 *   VALUE column to be declared NOT NULL (metadata guard: the divergent cell is then
 *   unreachable); NULLABLE value columns stay local.
 * - ties are nondeterministic on BOTH engines (no tie-break contract to preserve).
 *
 * Key types: the exact-ordering set ([DorisAggregatePushdown.isExactPushableKeyType] — the
 * key is what gets compared). Value types: the same set plus VARCHAR (payload passthrough,
 * byte fidelity proven; CHAR/REAL/DOUBLE excluded — trailing-space and wire-Infinity read
 * hazards). Result reuses the VALUE column's type handle (same proven read path).
 */
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
        val valueColumn = context.getAssignment(value.name) as JdbcColumnHandle
        val keyColumn = context.getAssignment(key.name) as JdbcColumnHandle
        verify(valueColumn.columnType == aggregateFunction.outputType)
        if (!DorisAggregatePushdown.isExactPushableKeyType(keyColumn.columnType)) {
            return DorisAggregatePushdown.rejected(
                "min_by/max_by KEY type not exact-pushable: ${keyColumn.columnType}",
                aggregateFunction,
            )
        }
        if (!isPushableValueType(valueColumn.columnType)) {
            return DorisAggregatePushdown.rejected(
                "min_by/max_by VALUE type not pushable: ${valueColumn.columnType}",
                aggregateFunction,
            )
        }
        if (valueColumn.isNullable) {
            return DorisAggregatePushdown.rejected(
                "min_by/max_by VALUE column is NULLABLE (Doris skips NULL-value rows, Trino keeps them): ${valueColumn.columnName}",
                aggregateFunction,
            )
        }
        val function = aggregateFunction.functionName
        return Optional.of(
            JdbcExpression(
                "$function(${context.rewriteExpression(value).orElseThrow().expression()}, " +
                    "${context.rewriteExpression(key).orElseThrow().expression()})",
                listOf(),
                valueColumn.jdbcTypeHandle,
            ),
        )
    }

    companion object {
        private val MIN_MAX_BY = setOf("min_by", "max_by")

        private fun isPushableValueType(type: Type): Boolean =
            DorisAggregatePushdown.isExactPushableKeyType(type) || type is io.trino.spi.type.VarcharType
    }
}

/**
 * `any_value(x)` -> Doris `any_value(x)` (READ-ONLY-MAX batch 2). Both engines are
 * NONDETERMINISTIC by contract, so only TYPE and NULL soundness are provable (and pinned):
 * both ignore NULL inputs (a group holding {NULL, 7} answers a NON-NULL value on both —
 * probed), and an all-NULL/empty group answers NULL on both. Differentials assert the
 * result is an element of the group, not a specific value. Argument types: the exact set
 * plus VARCHAR (payload passthrough), same exclusions as min_by values.
 */
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
        val column = context.getAssignment(argument.name) as JdbcColumnHandle
        verify(column.columnType == aggregateFunction.outputType)
        val pushable = DorisAggregatePushdown.isExactPushableKeyType(column.columnType) ||
            column.columnType is io.trino.spi.type.VarcharType
        if (!pushable) {
            return DorisAggregatePushdown.rejected("any_value argument type not pushable: ${column.columnType}", aggregateFunction)
        }
        return Optional.of(
            JdbcExpression(
                "any_value(${context.rewriteExpression(argument).orElseThrow().expression()})",
                listOf(),
                column.jdbcTypeHandle,
            ),
        )
    }

    companion object {
        private val ANY_ARGUMENT: Capture<Variable> = newCapture()
    }
}

/**
 * `approx_distinct(x)` -> Doris `approx_count_distinct(x)` — OPT-IN ONLY
 * (`doris.approximate-pushdown` / session `approximate_pushdown`, default FALSE): both are
 * HyperLogLog ESTIMATES but the sketches differ (different implementations, different
 * error profiles), so pushed and local answers are legitimately DIFFERENT numbers for the
 * same data. Off by default per the exactness discipline; when enabled the caller accepts
 * estimate divergence. Type soundness: BIGINT result on both; empty input -> 0 on both
 * (probed). Argument: any exact-set or varchar column.
 */
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
            return DorisAggregatePushdown.rejected("approximate pushdown disabled (doris.approximate-pushdown)", aggregateFunction)
        }
        val argument = captures.get(APPROX_ARGUMENT)
        val column = context.getAssignment(argument.name) as JdbcColumnHandle
        if (aggregateFunction.outputType != BIGINT) {
            return DorisAggregatePushdown.rejected("approx_distinct output type: ${aggregateFunction.outputType}", aggregateFunction)
        }
        val pushable = DorisAggregatePushdown.isExactPushableKeyType(column.columnType) ||
            column.columnType is io.trino.spi.type.VarcharType
        if (!pushable) {
            return DorisAggregatePushdown.rejected("approx_distinct argument type not pushable: ${column.columnType}", aggregateFunction)
        }
        return Optional.of(
            JdbcExpression(
                "approx_count_distinct(${context.rewriteExpression(argument).orElseThrow().expression()})",
                listOf(),
                DorisTypeMapping.toTypeHandle("bigint"),
            ),
        )
    }

    companion object {
        private val APPROX_ARGUMENT: Capture<Variable> = newCapture()
    }
}
