# HANDOFF — request to the brikk-house agent: `brikk-sql-metadata` additive change

**From:** `trino-doris` connector (Trino 483 read-only Apache Doris connector, this repo,
branch `trino-doris`).
**To:** the agent working in `brikk-house`.
**Date:** 2026-07-19.

> ## FULFILLED — 2026-07-19, `brikk-sql-metadata-jvm:0.7.0` on Maven Central
> brikk shipped the request with one improvement over the letter of this document: the
> registry became DIRECTION-ORIENTED — `sourceName`/`targetName` are populated per lookup
> direction (a `trino->doris` lookup carries the Trino name as source and the probed Doris
> name as target), which is strictly better for our drift pins than the raw JSON field
> passthrough requested below. `HazardRegistry.lookup` is unchanged, as requested. Verified
> entries used by this connector: contains->array_contains [DIVERGENT],
> arrays_overlap->arrays_overlap [CONDITIONALLY_EQUIVALENT],
> array_position->array_position [IDENTICAL] (plus regexp_like->regexp and
> json_extract_scalar->json_unquote for future rules). Artifact pinned + sha256-asserted in
> `TestDorisPushdownEvidenceDrift`; connector-construction cross-check in
> `DorisPushdownEvidence.verifyAgainstRegistry()`. This document is retained as the
> historical request.

This is the complete request. Nothing beyond what is written here is wanted.

## What we consume

`dev.brikk.house:brikk-sql-metadata-jvm` — currently released as `0.6.0` on Maven Central
(https://repo1.maven.org/maven2/dev/brikk/house/brikk-sql-metadata-jvm/0.6.0/). Verified:
the 0.6.0 jar contains `HazardVerdict`, `FunctionHazard`, `HazardRegistry`, and
`GeneratedTrinoDorisHazardsKt` (the 216 probe-verified trino→doris verdicts).

The connector pins hazard-registry entries as *evidence* for each pushdown rule
(rule → verdict + provenance + target function name) and runs drift tests so a future
brikk data change fails our build instead of silently changing semantics.

## The gap (verified against the 0.6.0 jar via javap)

`FunctionHazard` in 0.6.0 is:

```kotlin
data class FunctionHazard(
    val verdict: HazardVerdict,
    val hazard: String?,
    val areas: List<String>,
    val provenance: String,
)
```

The source/target function names are **not in the data** — the Doris target name survives
only as a source-code comment in the generated file. `HazardRegistry.lookup(source, target,
name)` therefore tells us a verdict but cannot tell us *which Doris function* the verdict
is about. Our drift tests need that name.

## Requested change — exactly this, nothing more

**1. Two additive, defaulted, nullable fields on `FunctionHazard`:**

```kotlin
@Serializable
data class FunctionHazard(
    val verdict: HazardVerdict,
    val hazard: String? = null,
    val areas: List<String> = emptyList(),
    val provenance: String,
    val sourceName: String? = null,   // NEW — source-dialect name, e.g. "contains"   (JSON field `trino`)
    val targetName: String? = null,   // NEW — probed target name, e.g. "array_contains" (JSON field `doris`)
)
```

**2. Populate them in the registry generator** (`tools/generate_hazards_registry.py`) from
the hazards JSON's existing `trino`/`doris` fields. Pure data-carry: both names already
exist in `brikk-sql/testResources/semantics/trino-doris-hazards.json`. No new probing, no
schema changes to the JSON, no verdict changes.

**3. Publish:**
- a local snapshot first (we will wire our drift tests against it immediately), then
- the next regular immutable release (e.g. `0.7.0`) to Maven Central like 0.6.0. Our
  production pin must be the release coordinate, not the snapshot (our plan gates G3/G12
  forbid shipping against a mutable SNAPSHOT).

## Explicitly NOT wanted

- No `lookupAll` / multi-target API. The one multi-target key we use
  (`json_extract_scalar` → `json_unquote` / `get_json_string`) we resolve by choosing
  `json_unquote` explicitly on our side; carrying `targetName` per entry is sufficient.
- No changes to `lookup(...)`'s signature, keying, or collision policy.
- No changes to verdicts, hazard text, areas, provenance, or the hazards JSON itself.
- No changes to `FunctionCatalog` or any other public API.
- No new dependencies in the module (it being featherweight — kotlinx-serialization only —
  is why we depend on it).

## Acceptance check (what we will verify on our side)

```kotlin
val h = HazardRegistry.lookup("trino", "doris", "contains")
check(h != null && h.verdict == HazardVerdict.DIVERGENT && h.targetName == "array_contains")
```

plus existing-behavior compatibility: deserialization of pre-change JSON and all existing
call-sites compile/run unchanged (both new fields defaulted).

## Background (optional reading, not part of the request)

Full audit with the gap analysis and proposal rationale:
`jvm/trino-doris/dev-docs/REPORT-brikk-metadata-api-audit.md` in this repo (§A.2 the shape
gap, §D the proposal, §A.3 correction note re: 0.6.0). Questions → the trino-doris session.
