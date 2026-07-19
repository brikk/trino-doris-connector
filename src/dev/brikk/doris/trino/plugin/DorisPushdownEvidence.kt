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
package dev.brikk.doris.trino.plugin

/**
 * Evidence bridge for every pushdown rule (PLAN §6.3 contract, G3 verdict-gating).
 *
 * The brikk `brikk-sql-metadata` artifact is UNRELEASED and PARKED pending user approval
 * (ledger §F; BRIKK audit) — so the hazard evidence is carried here as CITATIONS (per the
 * agreed boundary: comments referencing the audit reports, never copied brikk data files).
 * When the brikk pin lands, each entry gains the artifact coordinates + registry key without
 * reworking the rules: the rule classes reference these constants and nothing else changes.
 *
 * Every rule is additionally backed by connector-owned LIVE proof (this repo's P2b probes and
 * `TestDorisP2bPushdown` truth-table pins) — brikk supplies evidence, the connector owns the
 * exact typed rendering (PLAN §6.3: "No function is pushed merely because it appears in both
 * function catalogs").
 */
internal object DorisPushdownEvidence {
    internal data class Evidence(
        /** Trino source function name (hazard-JSON `trino` field, cited). */
        val trinoFunction: String,
        /** Doris target rendering (hazard-JSON `doris` field is name-level; the connector owns the shape). */
        val dorisRendering: String,
        /** Verdict per PLAN G3 gating (IDENTICAL / CONDITIONALLY_EQUIVALENT / DIVERGENT-with-explicit-rewrite). */
        val verdict: String,
        /** One-line hazard summary. */
        val hazard: String,
        /** Where the evidence lives. */
        val provenance: String,
        val brikkArtifactPin: String = "PENDING RELEASE (ledger §F: no released brikk-sql-metadata artifact exists; approval parked)",
    )

    /**
     * PLAN §6.2 row 1: "contains(array, value) -> array_contains(array, value) = 1 —
     * connector-original name/boolean-shape rewrite; brikk supplies the divergent-hazard
     * evidence. Prove NULL and element coercion." The live P2b truth table (2026-07-19,
     * Doris 4.1.3) proves: for a NON-NULL needle the only value divergence is
     * not-found-with-NULL-elements (Trino NULL vs Doris 0) — indistinguishable in top-level
     * WHERE-conjunct context, the ONLY context this connector emits (no NOT/IS NULL/boolean
     * composition rules exist). The `= 1` wrapper from the PLAN shape is deliberately DROPPED:
     * profile evidence shows it defeats Doris inverted-index acceleration
     * (`RowsInvertedIndexFiltered: 0` wrapped vs `997.997K` bare — see
     * evidence/inverted-index-explain-p2b.md), and bare tinyint truthiness is
     * predicate-equivalent.
     */
    val CONTAINS = Evidence(
        trinoFunction = "contains(array(T), T)",
        dorisRendering = "(array_contains(`col`, ?))  -- bare truthy form, predicate context only",
        verdict = "DIVERGENT (name + boolean shape) -> explicit connector rewrite per PLAN G3, live-proven",
        hazard = "Doris returns 0 where Trino returns NULL for not-found-in-array-with-NULL-elements; " +
            "equivalent only as a top-level WHERE conjunct (NULL and false both drop the row)",
        provenance = "PLAN §6.2 (brikk trino-doris-hazards.json cited therein, 216 pairs at P0 pin); " +
            "live truth table + profile probes 2026-07-19 vs Doris 4.1.3 (TestDorisP2bPushdown pins)",
    )

    /**
     * PLAN §6.2 row 2: "arrays_overlap -> arrays_overlap(...) = 1 — connector-original
     * predicate normalization over brikk's conditional value-equivalence evidence." Live P2b
     * truth table found the condition the brikk verdict warned about: Doris `arrays_overlap`
     * treats NULL elements as EQUAL (`[1,null] × [null,4]` -> 1) where Trino returns NULL —
     * a pushed bare rendering would OVER-RETURN (silently wrong). The guard wrapper
     * `array_filter(x -> x IS NOT NULL, left)` strips one side's NULLs (Doris never matches
     * NULL to a value, proven), restoring exact predicate-level equivalence on all 8 probed
     * shapes. This is the G3 CONDITIONALLY_EQUIVALENT path: "requires a connector rule with
     * explicit guards or wrappers".
     */
    val ARRAYS_OVERLAP = Evidence(
        trinoFunction = "arrays_overlap(array(T), array(T))",
        dorisRendering = "(arrays_overlap(array_filter(x -> x IS NOT NULL, `left`), `right`))",
        verdict = "CONDITIONALLY_EQUIVALENT -> guarded wrapper per PLAN G3, live-proven",
        hazard = "Doris matches NULL elements to each other (returns 1) where Trino returns NULL -> " +
            "unguarded pushdown OVER-RETURNS; fixed by stripping NULL elements from one side",
        provenance = "PLAN §6.2; live truth table probes 2026-07-19 vs Doris 4.1.3 " +
            "(both_null_elems: bare=1 vs fixed=0; TestDorisP2bPushdown pins)",
    )

    /**
     * PLAN §6.2 row 3: "array_position(array, value) in comparison — proven 1-based and
     * zero-if-absent; typed comparisons only." Live P2b truth table proves FULL value-level
     * equivalence for non-NULL needles: 1-based first occurrence (NULL elements counted
     * identically), 0 if absent (even with NULL elements present — both engines), NULL if the
     * array is NULL. Identical values make EVERY comparison operator exactly equivalent, so
     * all six operators are pushed with a non-NULL integer bound.
     */
    val ARRAY_POSITION = Evidence(
        trinoFunction = "array_position(array(T), T) <cmp> bigint-literal",
        dorisRendering = "(array_position(`col`, ?) <cmp> n)",
        verdict = "IDENTICAL (value-level, non-NULL needle) -> typed comparison rule per PLAN G3, live-proven",
        hazard = "none observed for non-NULL needles: 1-based/0-if-absent/NULL-array-propagation all match " +
            "(NULL needles are never pushed; Trino=NULL vs Doris=1 for NULL-needle-with-NULL-element)",
        provenance = "PLAN §6.2; live truth table probes 2026-07-19 vs Doris 4.1.3 (TestDorisP2bPushdown pins)",
    )
}
