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

/**
 * String predicate pushdown mode — catalog config `doris.string-pushdown.mode`, per-query
 * override via session property `string_pushdown_mode` (either direction: tighten or
 * loosen). Evidence: `REPORT-string-comparison-probe-4.1.3.md` — Doris 4.1.3 string
 * comparison/ordering/LIKE are pure byte (memcmp) semantics over UTF-8
 * (`utf8mb4_0900_bin`), identical to Trino's VARCHAR semantics except for the hazards each
 * mode addresses.
 */
enum class DorisStringPushdownMode {
    /** String domains push null-ness only (`IS [NOT] NULL`) — the pre-P4 conservative posture. */
    NULL_ONLY,

    /**
     * EVIDENCE-TIERED (the DEFAULT mode): VARCHAR/STRING equality/inequality/range/IN
     * domains with non-hazardous literals push FULLY — no retained filter — because each
     * shape is byte-exactness-proven by the probe report; this lets LIMIT/TopN-adjacent
     * plans collapse into a single remote scan. Genuine superset pre-filters (the
     * LIKE-prefix range) keep their exactness structurally: the engine retains the LIKE
     * expression itself. Probe-flagged hazards stay fully local: domains whose values
     * contain a 0x00 byte (one probe run observed a NUL-literal comparison return
     * wrong-empty under host memory pressure; reproductions are byte-exact, but a
     * once-observed silent miss earns a permanent skip), and CHAR columns categorically
     * (Doris compares stored bytes while Trino compares trimmed CHAR values —
     * trailing-space data under-returns undetectably). String TopN keys and the LIKE
     * rewrite remain BINARY/FULL-only.
     */
    GUARDED,

    /**
     * Full string pushdown under VERIFIED byte-comparison semantics. Doris 4.1.3 needs no
     * forcing mechanism (`@@collation_connection = utf8mb4_0900_bin`; the whole comparison
     * matrix is byte-exact), so BINARY shares the FULL rendering — the difference is the
     * contract: the byte-exactness assumption is probe-verified and pinned by tests.
     * Unlocks VARCHAR TopN and LIKE pushdown. The known, documented divergence is CHAR
     * trailing-space data (Trino trims, Doris compares stored bytes).
     */
    BINARY,

    /**
     * Full string pushdown, no retained filter — the caller asserts their workload is safe.
     * Same rendering and unlocks as [BINARY].
     */
    FULL,
    ;

    /** BINARY/FULL: no retained filter, string TopN + LIKE eligible. */
    val allowsFullStringPushdown: Boolean
        get() = this == BINARY || this == FULL
}
