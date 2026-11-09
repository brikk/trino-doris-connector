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

import com.google.common.collect.ImmutableSet
import io.trino.plugin.base.aggregation.AggregateFunctionRewriter
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.base.projection.ProjectFunctionRewriter
import io.trino.plugin.base.projection.ProjectFunctionRule
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.aggregation.ImplementCount
import io.trino.plugin.jdbc.aggregation.ImplementCountAll
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.plugin.jdbc.expression.RewriteVariable
import io.trino.spi.expression.StandardFunctions.COALESCE_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NULLIF_FUNCTION_NAME

/** Every rule that can push a predicate, projection or aggregate down to Doris. */
internal class DorisPushdownRewriters(typeMapping: DorisTypeMapping, quote: (String) -> String) {
    /**
     * Batch-1 SCALAR VALUE tier ([DorisScalarPushdownRules]): typed, composable rewrites of
     * coalesce/nullif/year..second/length over the closed value-type set.
     * Shared by the predicate bridges below and the projection path.
     */
    private val scalar = DorisRewriterHolder().also { holder ->
        holder.rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteScalarVariable(typeMapping, quote))
            .add(RewriteScalarConstant())
            .add(RewriteScalarFunctionByName(COALESCE_FUNCTION_NAME, "coalesce", 2..MAX_SCALAR_ARITY))
            .add(RewriteScalarFunctionByName(NULLIF_FUNCTION_NAME, "nullif", 2..2))
            .apply { listOf("year", "month", "day", "hour", "minute", "second").forEach { add(RewriteTemporalExtract(it)) } }
            // Doris 4.1.5 full/contextual Unicode folding differs from Trino. Keep
            // both functions and expressions containing them in the engine in every mode.
            .add(RewriteCharLength())
            .build()
    }

    /**
     * Typed ARRAY predicate rules (PLAN §6.1 layer 2, §6.2; P2b/P3) in two tiers:
     *
     * - VALUE-SAFE tier: `array_position` comparisons (value-identical on both engines,
     *   [DorisPushdownEvidence.ARRAY_POSITION]) plus NOT/AND/OR composition over that tier
     *   only — safe because Doris's three-valued logic is live-proven identical to Trino's
     *   (see [RewriteValueSafeNot] and the P3 composition pins).
     * - PREDICATE-LEVEL tier: `contains` / `arrays_overlap`, whose NULL cells differ at
     *   value level (Trino NULL vs Doris 0/1). They may ONLY surface as top-level WHERE
     *   conjuncts and are structurally excluded from composition: the composition rules
     *   rewrite children through the value-safe rewriter, which does not contain them.
     *
     * Deliberately NO `addStandardRules`: scalar column-vs-literal comparisons and
     * IS [NOT] NULL are already covered exactly by domain pushdown (P1a controllers);
     * column-to-column scalar comparison rules would be new unproven surface (P3 remainder).
     * Evidence citations: [DorisPushdownEvidence].
     */
    val predicate: ConnectorExpressionRewriter<ParameterizedExpression> = run {
        val support = DorisArrayPushdownSupport(typeMapping, quote)
        val valueSafe = DorisRewriterHolder()
        val valueSafeRules = listOf(
            RewriteArrayPositionComparison(support),
            // cardinality -> array_size: value-identical on every reachable cell (P5 pins),
            // so composable — [DorisPushdownEvidence.CARDINALITY]
            RewriteCardinalityComparison(support),
            RewriteValueSafeNot(valueSafe),
            RewriteValueSafeLogical(valueSafe),
        )
        valueSafe.rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .apply { valueSafeRules.forEach { add(it) } }
            .build()
        JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .apply { valueSafeRules.forEach { add(it) } }
            .add(RewriteContains(support))
            .add(RewriteArraysOverlap(support))
            // LIKE pushes only in BINARY/FULL string mode (rule.isEnabled gates per session);
            // stays out of the value-safe tier (no composition) — probe report, LIKE section.
            .add(RewriteStringLike(quote))
            // JSON equality is PREDICATE-level (divergent cells are both-drop only under a
            // top-level '='), so it too stays out of the value-safe tier —
            // [DorisPushdownEvidence.JSON_EXTRACT_SCALAR]
            .add(RewriteJsonExtractScalarEquality(quote))
            // Batch-1 scalar bridges: comparisons / IS NULL over scalar-tier function shapes
            // (plain column shapes stay domain territory) + starts_with -> escaped LIKE-prefix.
            // Top-level-conjunct tier (not composable under NOT/AND/OR).
            .add(RewriteScalarComparisonBridge(scalar))
            .add(RewriteScalarIsNullBridge(scalar))
            .add(RewriteStartsWith(quote))
            .build()
    }

    /**
     * P6 typed scalar PROJECTION pushdown ([RewriteCastDatetimeToDate], [RewriteDateTrunc]):
     * a converted projection becomes a synthetic derived column, which the aggregate
     * machinery can then GROUP BY remotely — `GROUP BY date(event_at)` collapses into one
     * Doris statement (proven end-to-end in TestDorisP6DateProjection). The scalar rewriter
     * resolves source-column assignments and validates their native type mappings.
     */
    val projection: ProjectFunctionRewriter<JdbcExpression, ParameterizedExpression> = ProjectFunctionRewriter(
        // the scalar tier doubles as the projection expression rewriter, so every scalar
        // shape (and composition) it accepts becomes a groupable synthetic column
        scalar.rewriter,
        ImmutableSet.of<ProjectFunctionRule<JdbcExpression, ParameterizedExpression>>(
            RewriteCastDatetimeToDate(),
            RewriteDateTrunc(),
            RewriteScalarProjection(),
        ),
    )

    /**
     * Verified aggregate pushdown (PLAN §6.5, P4), one live-proven family at a time. The
     * argument rewriter is DELIBERATELY only [RewriteVariable] (quote a column reference):
     * aggregate arguments must stay bare columns — no derived-expression surface. Family
     * verdicts (full proofs in `dev-docs/archive/NOTES-p4-aggregates.md`, pinned by
     * `TestDorisP4AggregateProbes`):
     *
     * - `count(*)` / `count(x)`: pushed (stock rules; bigint result; empty/all-NULL proven).
     * - `count(DISTINCT x)`, exact key types: pushed ([DorisImplementCountDistinct]).
     * - `min/max`, exact key types: pushed ([DorisImplementMinMax]); text (collation) and
     *   REAL/DOUBLE (Doris max = NaN vs Trino 483 max = Infinity — divergent) stay local.
     * - `sum(DECIMAL(p<=18, s))`: pushed ([DorisImplementSum]); every other sum family stays
     *   local — Doris sums wrap SILENTLY (bigint at 2^64, decimal/largeint at 2^128) where
     *   Trino throws.
     * - `avg`: NEVER pushed. Doris avg(DECIMALV3(p, s)) computes at scale max(s, 4) with
     *   TRUNCATION (live: avg{0.0001, 0.0000} = 0.0000 vs Trino HALF_UP 0.0001 at s=4) and
     *   silently corrupts at p=38 (intermediate overflow); Doris avg(BIGINT) computes an
     *   EXACT wide integer sum (live: avg{2^53, 1, -2^53, 1} = 0.5) while Trino 483
     *   accumulates in DOUBLE (order-dependent, lossy — BigintAverageAggregations). Kept
     *   local, Trino semantics preserved.
     */
    val aggregate: AggregateFunctionRewriter<JdbcExpression, ParameterizedExpression> = AggregateFunctionRewriter(
        JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteVariable { name -> quote(name) })
            .build(),
        ImmutableSet.of(
            ImplementCountAll(BIGINT_TYPE_HANDLE),
            ImplementCount(BIGINT_TYPE_HANDLE),
            DorisImplementCountDistinct(),
            DorisImplementMinMax(),
            DorisImplementSum(),
            DorisImplementMinMaxBy(),
            DorisImplementAnyValue(),
            DorisImplementApproxDistinct(),
        ),
    )

    companion object {
        private const val MAX_SCALAR_ARITY = 10

        /** Synthesized result handle for remote BIGINT aggregate results (count family). */
        private val BIGINT_TYPE_HANDLE: JdbcTypeHandle = DorisTypeMapping.toTypeHandle("bigint")
    }
}
