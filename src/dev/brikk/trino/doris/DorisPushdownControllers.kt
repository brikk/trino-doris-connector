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
import io.trino.plugin.jdbc.PredicatePushdownController
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult
import io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.predicate.Domain
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimestampType
import io.trino.spi.type.Type
import java.time.LocalDate
import java.util.concurrent.TimeUnit.DAYS

/** Domain (predicate) pushdown decisions for the column types whose values need a guard. */
internal object DorisPushdownControllers {
    private val MIN_DORIS_DATE = LocalDate.of(0, 1, 1).toEpochDay()
    private val MAX_DORIS_DATE = LocalDate.of(9999, 12, 31).toEpochDay()
    private val MIN_DORIS_TIMESTAMP = DAYS.toMicros(MIN_DORIS_DATE)
    private val DORIS_TIMESTAMP_UPPER_BOUND = DAYS.toMicros(MAX_DORIS_DATE + 1)

    /** Doris turns out-of-range DATE/DATETIME parameters into NULL, which changes comparison results. */
    fun isWithinTemporalRange(type: Type, value: Any?): Boolean = when {
        type == DATE -> value is Long && value in MIN_DORIS_DATE..MAX_DORIS_DATE
        type is TimestampType -> value is Long && value >= MIN_DORIS_TIMESTAMP && value < DORIS_TIMESTAMP_UPPER_BOUND
        else -> true
    }

    val TEMPORAL_PUSHDOWN = PredicatePushdownController { session, domain ->
        val inRange = domain.values.valuesProcessor.transform(
            { ranges ->
                ranges.orderedRanges.all { range ->
                    range.lowValue.map { isWithinTemporalRange(domain.type, it) }.orElse(true) &&
                        range.highValue.map { isWithinTemporalRange(domain.type, it) }.orElse(true)
                }
            },
            { discrete -> discrete.values.all { isWithinTemporalRange(domain.type, it) } },
            { true },
        )
        if (inRange) FULL_PUSHDOWN.apply(session, domain) else DISABLE_PUSHDOWN.apply(session, domain)
    }

    /**
     * Doris compares strings as UTF-8 bytes (`utf8mb4_0900_bin`), matching Trino's VARCHAR
     * comparison, so GUARDED mode pushes domains fully with no local re-check. A LIKE-prefix
     * range stays exact because the engine keeps the LIKE itself as a filter.
     */
    val VARCHAR_PUSHDOWN = PredicatePushdownController { session, domain ->
        when (DorisSessionProperties.getStringPushdownMode(session)) {
            DorisStringPushdownMode.NULL_ONLY -> nullOnlyResult(session, domain)
            DorisStringPushdownMode.GUARDED -> guardedResult(session, domain)
            DorisStringPushdownMode.BINARY, DorisStringPushdownMode.FULL -> FULL_PUSHDOWN.apply(session, domain)
        }
    }

    /**
     * Doris compares CHAR as stored bytes while Trino ignores trailing spaces, so rows whose
     * stored value has trailing spaces silently go missing. Only BINARY/FULL mode accepts that.
     */
    val CHAR_PUSHDOWN = PredicatePushdownController { session, domain ->
        if (DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown) {
            FULL_PUSHDOWN.apply(session, domain)
        } else {
            nullOnlyResult(session, domain)
        }
    }

    /** An IPv6 value can never equal an IPV4 column and has no IPV4 literal, so such domains stay local. */
    val IPV4_PUSHDOWN = PredicatePushdownController { session, domain ->
        if (collectDomainSlices(domain).all(DorisIpAddress::isV4Mapped)) {
            FULL_PUSHDOWN.apply(session, domain)
        } else {
            DISABLE_PUSHDOWN.apply(session, domain)
        }
    }

    fun containsNulByte(slice: Slice): Boolean =
        (0 until slice.length()).any { slice.getByte(it) == 0.toByte() }

    private fun nullOnlyResult(session: ConnectorSession, domain: Domain): DomainPushdownResult {
        return if (domain.isOnlyNull || domain.values.isAll) {
            DomainPushdownResult(domain, Domain.all(domain.type))
        } else {
            DISABLE_PUSHDOWN.apply(session, domain)
        }
    }

    private fun guardedResult(session: ConnectorSession, domain: Domain): DomainPushdownResult {
        if (collectDomainSlices(domain).any(::containsNulByte)) {
            // Doris can wrongly return no rows for a comparison with a 0x00 literal.
            return DISABLE_PUSHDOWN.apply(session, domain)
        }
        // If the domain is simplified, FULL_PUSHDOWN keeps the original as the remaining filter.
        return FULL_PUSHDOWN.apply(session, domain)
    }

    private fun collectDomainSlices(domain: Domain): List<Slice> {
        val slices = ArrayList<Slice>()
        domain.values.valuesProcessor.consume(
            { ranges ->
                ranges.orderedRanges.forEach { range ->
                    range.lowValue.ifPresent { slices.add(it as Slice) }
                    range.highValue.ifPresent { slices.add(it as Slice) }
                }
            },
            { discrete -> discrete.values.forEach { slices.add(it as Slice) } },
            { /* all-or-none carries no values */ },
        )
        return slices
    }
}
