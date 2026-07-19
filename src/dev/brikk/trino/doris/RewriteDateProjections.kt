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

/**
 * P6 date-of-datetime PROJECTION pushdown (the user's motivating shape:
 * `GROUP BY date(event_at)` on huge tables — the projection becomes a synthetic derived
 * column that the aggregate machinery then groups on remotely, collapsing the whole
 * query into one Doris statement).
 *
 * `CAST(datetime_col AS DATE)` -> `CAST(col AS DATE)` — registry-IDENTICAL
 * ([DorisPushdownEvidence.DATE_OF_DATETIME]); live-probed identical to Doris `date()`
 * on every precision (0/3/6) incl. the `0000-01-01` / `9999-12-31` edges. Trino's
 * `date(x)` spelling desugars to this cast, so one rule covers both spellings.
 * Source restricted to plain DATETIME columns (Variable of TIMESTAMP(0..6)).
 */
internal class RewriteCastDatetimeToDate(
    private val quote: (String) -> String,
) : ProjectFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = PATTERN

    override fun rewrite(
        handle: io.trino.spi.connector.ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val call = expression as Call
        val source = pushableDatetimeColumn(call.arguments[0]) ?: return Optional.empty()
        return Optional.of(
            JdbcExpression(
                "CAST(${quote(source.name)} AS DATE)",
                listOf(),
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
 * `date_trunc('unit', datetime_col)` -> `date_trunc(col, 'unit')` — the ARGUMENT ORDER
 * SWAPS (registry CONDITIONALLY_EQUIVALENT, [DorisPushdownEvidence.DATE_TRUNC]); returns
 * DATETIME of the input's precision in both engines (live-probed). Unit allowlist is
 * live-proven per unit (`NOTES-p6-date-projection.md`): second/minute/hour/day/month/
 * quarter/year truncate identically. WEEK is DENIED: both engines are Monday-start
 * (probed on Sunday/Monday/ISO-week-1 adversaries), but at the calendar minimum Trino
 * truncates 0000-01-01/02 into year -1 (`-0001-12-27`) while Doris CLAMPS to
 * `0000-01-01` — a data-dependent divergence no literal guard can see (pinned in
 * `testEdgeDates`). millisecond is REJECTED by Doris ("time unit param only support
 * year|quarter|month|week|day|hour|minute|second") and stays local (Trino itself
 * rejects `microsecond`). Non-constant units never push.
 */
internal class RewriteDateTrunc(
    private val quote: (String) -> String,
) : ProjectFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = PATTERN

    override fun rewrite(
        handle: io.trino.spi.connector.ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val call = expression as Call
        val unit = constantUnit(call.arguments[0]) ?: return Optional.empty()
        val source = pushableDatetimeColumn(call.arguments[1]) ?: return Optional.empty()
        val resultType = call.type as? TimestampType ?: return Optional.empty()
        if (resultType.precision > DORIS_MAX_DATETIME_PRECISION) {
            return Optional.empty()
        }
        return Optional.of(
            JdbcExpression(
                "date_trunc(${quote(source.name)}, '$unit')",
                listOf(),
                DorisTypeMapping.toTypeHandle("datetime(${resultType.precision})"),
            ),
        )
    }

    /** Constant lowercase unit from the live-proven allowlist only (inline-safe by construction). */
    private fun constantUnit(argument: ConnectorExpression): String? {
        val constant = argument as? Constant ?: return null
        if (constant.type !is VarcharType) {
            return null
        }
        val text = (constant.value as? Slice)?.toStringUtf8() ?: return null
        return text.takeIf { it in PROVEN_UNITS }
    }

    companion object {
        /** Live-proven identical truncation semantics per unit (probe + differential pins; week denied — see KDoc). */
        private val PROVEN_UNITS = setOf("second", "minute", "hour", "day", "month", "quarter", "year")

        private const val DORIS_MAX_DATETIME_PRECISION = 6

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("date_trunc")))
            .with(argumentCount().equalTo(2))
    }
}

/** A plain native DATETIME column reference: Variable of TIMESTAMP(0..6). */
private fun pushableDatetimeColumn(argument: ConnectorExpression): Variable? {
    val variable = argument as? Variable ?: return null
    val type = variable.type as? TimestampType ?: return null
    return variable.takeIf { type.precision <= 6 }
}
