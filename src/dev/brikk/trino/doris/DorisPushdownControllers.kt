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

    /** Checks Trino epoch days/microseconds without JDBC conversion; other types are unaffected. */
    fun isWithinTemporalRange(type: Type, value: Any?): Boolean = when {
        type == DATE -> value is Long && value in MIN_DORIS_DATE..MAX_DORIS_DATE
        type is TimestampType -> value is Long && value >= MIN_DORIS_TIMESTAMP && value < DORIS_TIMESTAMP_UPPER_BOUND
        else -> true
    }

    // Doris casts out-of-range temporal parameters to NULL, changing comparison semantics.
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
     * G5 string-domain policy, mode-resolved per query (probe evidence:
     * `dev-docs/archive/REPORT-string-comparison-probe-4.1.3.md` — Doris 4.1.3 string comparison is pure
     * byte semantics, `utf8mb4_0900_bin`):
     * - NULL_ONLY: null-ness only (exact; no collation hazard).
     * - GUARDED (default), EVIDENCE-TIERED: domain shapes (`=`, `<>`, `IN`, ranges) over
     *   non-hazardous literals push FULLY — each shape is byte-exactness-proven by the
     *   probe report (equality/case/padding/NFC-vs-NFD rows; `<`/`BETWEEN`/`IN` rows;
     *   byte order == codepoint order), so a retained local re-check is pure overhead
     *   and blocks LIMIT/TopN collapse. Genuine SUPERSET pre-filters keep their local
     *   check STRUCTURALLY: the LIKE-prefix range works because the engine derives the
     *   range domain and retains the un-convertible LIKE expression ITSELF — the
     *   superset relationship lives between the domain and the LIKE, not inside the
     *   domain, so full domain pushdown preserves it. Hazards stay fully local:
     *   0x00-bearing values (defense-in-depth: one transient wrong-empty observation,
     *   probe report) and CHAR columns (see [CHAR_PUSHDOWN]).
     * - BINARY/FULL: full pushdown incl. TopN keys and the LIKE rule (byte-exactness
     *   probe-verified; the known documented divergence is CHAR trailing-space data).
     */
    val VARCHAR_PUSHDOWN = PredicatePushdownController { session, domain ->
        when (DorisSessionProperties.getStringPushdownMode(session)) {
            DorisStringPushdownMode.NULL_ONLY -> nullOnlyResult(session, domain)
            DorisStringPushdownMode.GUARDED -> guardedResult(session, domain)
            DorisStringPushdownMode.BINARY, DorisStringPushdownMode.FULL -> FULL_PUSHDOWN.apply(session, domain)
        }
    }

    /**
     * CHAR columns: Doris compares STORED bytes (no padding) while Trino compares
     * trimmed CHAR values — stored trailing-space data UNDER-returns and is undetectable
     * from the query (probe report, CHAR row). GUARDED therefore keeps CHAR at null-ness
     * only; BINARY/FULL push with the divergence documented and tested.
     */
    val CHAR_PUSHDOWN = PredicatePushdownController { session, domain ->
        if (DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown) {
            FULL_PUSHDOWN.apply(session, domain)
        } else {
            nullOnlyResult(session, domain)
        }
    }

    /**
     * Push an IPV4 domain ONLY when every value is an IPv4-mapped address ([DorisIpAddress.isV4Mapped]);
     * a real IPv6 literal can never equal an IPV4 column value, so keeping such a domain local
     * is both correct and lets [DorisIpAddress.writeFunction] always emit a dotted-quad literal.
     */
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
            // Null-ness has no collation hazard; pushing it is exact.
            DomainPushdownResult(domain, Domain.all(domain.type))
        } else {
            DISABLE_PUSHDOWN.apply(session, domain)
        }
    }

    private fun guardedResult(session: ConnectorSession, domain: Domain): DomainPushdownResult {
        if (collectDomainSlices(domain).any(::containsNulByte)) {
            // hazard skip: a NUL-literal miss was observed once (transient, probe
            // report) — keeping these fully local is always correct
            return DISABLE_PUSHDOWN.apply(session, domain)
        }
        // evidence-tiered: every remaining domain shape is byte-exactness-proven, so it
        // pushes FULLY (FULL_PUSHDOWN also handles compaction correctly: an over-wide
        // simplified domain keeps the original as the remaining filter)
        return FULL_PUSHDOWN.apply(session, domain)
    }

    /** Gathers every Slice in a domain (discrete values and range bounds). */
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
