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
    private val scalar = DorisRewriterHolder().also { holder ->
        holder.rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteScalarVariable(typeMapping, quote))
            .add(RewriteScalarConstant())
            .add(RewriteScalarFunctionByName(COALESCE_FUNCTION_NAME, "coalesce", 2..MAX_SCALAR_ARITY))
            .add(RewriteScalarFunctionByName(NULLIF_FUNCTION_NAME, "nullif", 2..2))
            .apply { listOf("year", "month", "day", "hour", "minute", "second").forEach { add(RewriteTemporalExtract(it)) } }
            // No lower/upper in any mode: Doris folds case differently from Trino on stored
            // columns (for example upper('ß') is 'SS' in Doris and 'ß' in Trino).
            .add(RewriteCharLength())
            .build()
    }

    /**
     * Two tiers. Value-safe rules return the same value as Trino for every input, NULL included,
     * so they may be composed under NOT/AND/OR. The other rules agree with Trino only on which
     * rows a top-level WHERE conjunct keeps, so they are never composed: the composition rules
     * rewrite their children through the value-safe rewriter, which does not contain them.
     *
     * No `addStandardRules`: column-to-literal comparisons and IS NULL already push as domains,
     * and column-to-column comparisons are not pushed.
     */
    val predicate: ConnectorExpressionRewriter<ParameterizedExpression> = run {
        val support = DorisArrayPushdownSupport(typeMapping, quote)
        val valueSafe = DorisRewriterHolder()
        val valueSafeRules = listOf(
            RewriteArrayPositionComparison(support),
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
            .add(RewriteStringLike(quote))
            .add(RewriteJsonExtractScalarEquality(quote))
            .add(RewriteScalarComparisonBridge(scalar))
            .add(RewriteScalarIsNullBridge(scalar))
            .add(RewriteStartsWith(quote))
            .build()
    }

    /**
     * A pushed projection becomes a synthetic column that aggregate pushdown can group on, so
     * `GROUP BY date(event_at)` runs in Doris. Any expression the scalar tier accepts qualifies.
     */
    val projection: ProjectFunctionRewriter<JdbcExpression, ParameterizedExpression> = ProjectFunctionRewriter(
        scalar.rewriter,
        ImmutableSet.of<ProjectFunctionRule<JdbcExpression, ParameterizedExpression>>(
            RewriteCastDatetimeToDate(),
            RewriteDateTrunc(),
            RewriteScalarProjection(),
        ),
    )

    /**
     * Aggregate arguments must be bare columns, so the only expression rule is [RewriteVariable].
     * `avg` is never pushed: on DECIMAL Doris truncates at scale max(s, 4) where Trino rounds half
     * up, and can overflow at precision 38; on BIGINT Doris sums exactly where Trino averages in
     * DOUBLE.
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

        private val BIGINT_TYPE_HANDLE: JdbcTypeHandle = DorisTypeMapping.toTypeHandle("bigint")
    }
}
