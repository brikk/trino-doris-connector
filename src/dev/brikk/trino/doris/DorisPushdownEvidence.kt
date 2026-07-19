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

import dev.brikk.house.sql.metadata.FunctionHazard
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException

/**
 * Evidence bridge for every pushdown rule (PLAN §6.3 contract, G3 verdict-gating), backed by
 * the RELEASED `dev.brikk.house:brikk-sql-metadata-jvm:0.7.0` hazard registry (the P2 exit
 * criterion "mapping API released and pinned" is CLOSED — 0.7.0 carries the
 * direction-oriented `sourceName`/`targetName` fields this connector requested).
 *
 * Trust model: the tuples pinned HERE are the source of truth the connector was proven
 * against; [HazardRegistry] is CROSS-CHECKED against them at connector construction
 * ([verifyAgainstRegistry], invoked from [DorisClientModule]) and again by the drift suite
 * (`TestDorisPushdownEvidenceDrift`, which also pins the artifact version + jar sha256).
 * A silently-changed future brikk artifact therefore cannot alter connector behavior — it
 * can only FAIL LOUD (catalog creation refuses; the build's drift suite goes red).
 *
 * Every rule remains additionally backed by connector-owned LIVE proof (truth-table pins and
 * differentials) — brikk supplies evidence, the connector owns the exact typed rendering
 * (PLAN §6.3: "No function is pushed merely because it appears in both function catalogs").
 */
internal object DorisPushdownEvidence {
    /** G3 gating category — how a verdict must be treated by the implementing rule. */
    internal enum class Treatment {
        /** IDENTICAL: direct typed rule. */
        DIRECT,

        /** CONDITIONALLY_EQUIVALENT: rule must carry an explicit guard/wrapper ([Evidence.guard]). */
        GUARDED_WRAPPER,

        /** DIVERGENT: connector-original rewrite, requires a dedicated live test ([Evidence.liveProof]). */
        CONNECTOR_ORIGINAL_REWRITE,
    }

    internal data class Evidence(
        /** Trino source shape (documentation). */
        val trinoFunction: String,
        /** Registry lookup key == hazard-JSON `trino` field. */
        val registrySource: String,
        /** Pinned registry `targetName` (hazard-JSON `doris` field). */
        val expectedTarget: String,
        /** Pinned registry verdict. */
        val expectedVerdict: HazardVerdict,
        /** Pinned registry provenance string. */
        val expectedProvenance: String,
        /** What the rule actually renders (must contain [expectedTarget]). */
        val dorisRendering: String,
        /** G3 treatment implementing the verdict. */
        val treatment: Treatment,
        /** For GUARDED_WRAPPER: the exact guard fragment present in the rendering. */
        val guard: String? = null,
        /** Connector-owned one-line hazard summary. */
        val hazard: String,
        /** The live proof (test class) backing the rule's safety argument. */
        val liveProof: String,
    )

    const val ARTIFACT_PIN = "dev.brikk.house:brikk-sql-metadata-jvm:0.7.0"
    const val ARTIFACT_SHA256 = "693575c17a041a0370b44a233969350f95d27c9bb47b14ebbbd1c80d2d3b1de8"

    private const val DIFFERENTIAL_PROBE_PROVENANCE = "REPORT-doris-differential-probe-2026-07-13.md#batch7-array"

    /**
     * `contains` does not exist in Doris; the DIVERGENT verdict is exactly why this is a
     * connector-original rewrite per G3. Rendered BARE (no `= 1` wrapper): profile-proven to
     * be the only form Doris accelerates with an ARRAY inverted index
     * (`evidence/inverted-index-explain-p2b.md`), and predicate-equivalent because this
     * connector emits it exclusively as a top-level WHERE conjunct (no composition rules for
     * predicate-level rewrites — enforced structurally).
     */
    val CONTAINS = Evidence(
        trinoFunction = "contains(array(T), T)",
        registrySource = "contains",
        expectedTarget = "array_contains",
        expectedVerdict = HazardVerdict.DIVERGENT,
        expectedProvenance = DIFFERENTIAL_PROBE_PROVENANCE,
        dorisRendering = "(array_contains(`col`, ?))  -- bare truthy form, predicate context only",
        treatment = Treatment.CONNECTOR_ORIGINAL_REWRITE,
        hazard = "Doris has no 'contains'; array_contains returns 0 where Trino returns NULL for " +
            "not-found-in-array-with-NULL-elements — equivalent only as a top-level WHERE conjunct",
        liveProof = "TestDorisP2bPushdown",
    )

    /**
     * The CONDITIONALLY_EQUIVALENT condition found live: Doris `arrays_overlap` treats NULL
     * elements as EQUAL (`[1,null] × [null,4]` -> 1) where Trino returns NULL — unguarded
     * pushdown would OVER-RETURN. The mandatory guard strips one side's NULLs (Doris never
     * matches NULL to a value), restoring exact predicate-level equivalence on all probed
     * shapes — G3's "requires a connector rule with explicit guards or wrappers".
     */
    val ARRAYS_OVERLAP = Evidence(
        trinoFunction = "arrays_overlap(array(T), array(T))",
        registrySource = "arrays_overlap",
        expectedTarget = "arrays_overlap",
        expectedVerdict = HazardVerdict.CONDITIONALLY_EQUIVALENT,
        expectedProvenance = DIFFERENTIAL_PROBE_PROVENANCE,
        dorisRendering = "(arrays_overlap(array_filter(x -> x IS NOT NULL, `left`), `right`))",
        treatment = Treatment.GUARDED_WRAPPER,
        guard = "array_filter(x -> x IS NOT NULL",
        hazard = "Doris matches NULL elements to each other (returns 1) where Trino returns NULL -> " +
            "unguarded pushdown OVER-RETURNS; fixed by stripping NULL elements from one side",
        liveProof = "TestDorisP2bPushdown",
    )

    /**
     * IDENTICAL at value level for non-NULL needles (1-based, 0-if-absent, NULL-array
     * propagation all match — live truth-table pins), so every comparison operator is exactly
     * safe, and NOT/AND/OR composition over these comparisons is safe too (Doris 3VL proven
     * cell-identical, `TestDorisP3Composition`).
     */
    val ARRAY_POSITION = Evidence(
        trinoFunction = "array_position(array(T), T) <cmp> bigint-literal",
        registrySource = "array_position",
        expectedTarget = "array_position",
        expectedVerdict = HazardVerdict.IDENTICAL,
        expectedProvenance = DIFFERENTIAL_PROBE_PROVENANCE,
        dorisRendering = "(array_position(`col`, ?) <cmp> n)",
        treatment = Treatment.DIRECT,
        hazard = "none observed for non-NULL needles (NULL needles are never pushed: " +
            "Trino=NULL vs Doris=1 for NULL-needle-with-NULL-element)",
        liveProof = "TestDorisP2bPushdown",
    )

    /**
     * Registry-IDENTICAL for the scalar rendering (`json_unquote(json_extract(...))` matches
     * Trino's unquoted scalar text — the 0.7.0 entry documents the generator fix that
     * introduced the wrap). The connector's own P5 probe found two cells the registry tuple
     * does not cover, both handled by rule-side SCOPE guards (not rendering changes, so the
     * treatment stays DIRECT, same policy as ARRAY_POSITION's never-pushed NULL needles):
     * non-scalar path values (Trino NULL vs Doris JSON text -> literals starting `{`/`[`
     * never push; `<>`/IS-NULL forms never push) and independent number canonicalization
     * (Doris `1e+30`/`-0` vs Trino `1E+30`/`0` -> numeric-looking literals never push).
     */
    val JSON_EXTRACT_SCALAR = Evidence(
        trinoFunction = "json_extract_scalar(json, varchar-path) = varchar-literal",
        registrySource = "json_extract_scalar",
        expectedTarget = "json_unquote",
        expectedVerdict = HazardVerdict.IDENTICAL,
        expectedProvenance = "REPORT-doris-differential-probe-2026-07-13.md#batch12-bucketb-trino; " +
            "generator fixed in BUGS-doris-generator-mappings-2026-07-13",
        dorisRendering = "(json_unquote(json_extract(`col`, ?)) = ?)  -- equality only, guarded literal/path shapes",
        treatment = Treatment.DIRECT,
        hazard = "non-scalar path values: Trino NULL vs Doris JSON text ('{...}'/'[...]') -> " +
            "equality-only with {/[-prefixed literals excluded; number text canonicalization " +
            "diverges (1e+30 vs 1E+30, -0 vs 0) -> numeric-looking literals excluded",
        liveProof = "TestDorisP5Batch",
    )

    /**
     * DIVERGENT in the registry purely as a RENAME (Doris has no array `cardinality`; the
     * portable name is `array_size`) — G3's connector-original-rewrite lane. Value-level
     * identity live-proven on every reachable cell (NULL array -> NULL, empty -> 0, NULL
     * elements counted, NOT/AND/OR composition cell-identical), so the rule sits in the
     * VALUE-SAFE composable tier with `array_position`.
     */
    val CARDINALITY = Evidence(
        trinoFunction = "cardinality(array(T)) <cmp> bigint-literal | BETWEEN lo AND hi",
        registrySource = "cardinality",
        expectedTarget = "array_size",
        expectedVerdict = HazardVerdict.DIVERGENT,
        expectedProvenance = DIFFERENTIAL_PROBE_PROVENANCE,
        dorisRendering = "(array_size(`col`) <cmp> n) | (array_size(`col`) BETWEEN lo AND hi)",
        treatment = Treatment.CONNECTOR_ORIGINAL_REWRITE,
        hazard = "pure rename (Doris lacks array 'cardinality'; array_size is the portable name); " +
            "map-cardinality is out of scope (no native MAP columns exist in this connector)",
        liveProof = "TestDorisP5Batch",
    )

    /**
     * `date(datetime)` (Trino spells it `CAST(x AS DATE)` after desugaring) — registry
     * IDENTICAL; connector probe re-verified both Doris renderings (`date(col)` and
     * `CAST(col AS DATE)`) identical on every precision incl. the 0000/9999 edges.
     * PROJECTION-position rule ([RewriteCastDatetimeToDate]).
     */
    val DATE_OF_DATETIME = Evidence(
        trinoFunction = "CAST(timestamp(0..6) AS DATE)  [date(x) desugars to this]",
        registrySource = "date",
        expectedTarget = "date",
        expectedVerdict = HazardVerdict.IDENTICAL,
        expectedProvenance = "REPORT-doris-differential-probe-2026-07-13.md#batch6-datetime",
        dorisRendering = "CAST(`col` AS DATE)  -- probe-identical to Doris date(`col`)",
        treatment = Treatment.DIRECT,
        hazard = "none observed: date part extraction is byte-identical on 0000-01-01/9999-12-31 " +
            "edges and all datetime precisions",
        liveProof = "TestDorisP6DateProjection",
    )

    /**
     * The CONDITIONALLY_EQUIVALENT condition: the ARGUMENT ORDER SWAPS (Trino
     * `date_trunc(unit, x)` vs Doris `date_trunc(x, unit)`) and the unit vocabulary
     * differs — Doris rejects millisecond/microsecond. The rule renders the swapped order
     * and pushes only the live-proven unit allowlist ([RewriteDateTrunc]).
     */
    val DATE_TRUNC = Evidence(
        trinoFunction = "date_trunc('second..year', timestamp(0..6))",
        registrySource = "date_trunc",
        expectedTarget = "date_trunc",
        expectedVerdict = HazardVerdict.CONDITIONALLY_EQUIVALENT,
        expectedProvenance = "REPORT-doris-differential-probe-2026-07-13.md#batch6-datetime",
        dorisRendering = "date_trunc(`col`, 'unit')  -- argument order swapped vs Trino",
        treatment = Treatment.GUARDED_WRAPPER,
        guard = "date_trunc(`col`, 'unit')",
        hazard = "argument order swaps; Doris rejects millisecond (stays local); WEEK denied: " +
            "Monday-start on both engines but Doris clamps year-0 week starts to 0000-01-01 " +
            "where Trino truncates into year -1 — data-dependent divergence",
        liveProof = "TestDorisP6DateProjection",
    )

    /** Every rule the connector registers — one Evidence entry per pushdown rule, no more, no fewer. */
    val ALL: List<Evidence> = listOf(
        CONTAINS, ARRAYS_OVERLAP, ARRAY_POSITION, JSON_EXTRACT_SCALAR, CARDINALITY, DATE_OF_DATETIME, DATE_TRUNC,
    )

    /**
     * Fail-loud registry cross-check, run at connector construction (from
     * [DorisClientModule.setup]): refuses to construct the connector when the registry entry
     * is missing or any pinned field drifted. Behavior can never silently follow the
     * artifact — the pins here win or nothing runs.
     */
    fun verifyAgainstRegistry() {
        ALL.forEach(::verify)
    }

    internal fun verify(evidence: Evidence) {
        val entry = HazardRegistry.lookup("trino", "doris", evidence.registrySource)
            ?: fail(evidence, "no trino->doris registry entry")
        if (entry.verdict != evidence.expectedVerdict) {
            fail(evidence, "verdict drifted: registry=${entry.verdict}, pinned=${evidence.expectedVerdict}")
        }
        if (entry.sourceName != evidence.registrySource) {
            fail(evidence, "sourceName drifted: registry=${entry.sourceName}, pinned=${evidence.registrySource}")
        }
        if (entry.targetName != evidence.expectedTarget) {
            fail(evidence, "targetName drifted: registry=${entry.targetName}, pinned=${evidence.expectedTarget}")
        }
        if (entry.provenance != evidence.expectedProvenance) {
            fail(evidence, "provenance drifted: registry=${entry.provenance}, pinned=${evidence.expectedProvenance}")
        }
        // structural self-check: the rule's rendering really uses the pinned target function
        if (!evidence.dorisRendering.contains(evidence.expectedTarget)) {
            fail(evidence, "rendering '${evidence.dorisRendering}' does not use pinned target '${evidence.expectedTarget}'")
        }
    }

    internal fun lookup(registrySource: String): FunctionHazard? =
        HazardRegistry.lookup("trino", "doris", registrySource)

    private fun fail(evidence: Evidence, reason: String): Nothing {
        throw TrinoException(
            GENERIC_INTERNAL_ERROR,
            "Pushdown evidence drift for '${evidence.registrySource}' against $ARTIFACT_PIN: $reason. " +
                "Refusing to construct the Doris connector — re-verify the rule against live Doris " +
                "and re-pin (PLAN G3/§6.3).",
        )
    }
}
