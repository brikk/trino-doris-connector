# REPORT — brikk-sql-metadata API audit for `trino-doris` P0

**Status:** Research only. No `brikk-house` files were modified (read-only audit).
No proposal in section (d) has been implemented — it requires user approval per AGENTS.md
and PLAN gate G3/G12.

**Scope:** Determine whether the currently *released* `brikk-sql-metadata` API is sufficient
as the connector's pinned evidence source (PLAN §6.3, gates G3 and G12), and if not, draft the
smallest additive API/release proposal.

**Audited repository (read-only):** `/home/jayson/DEV/brikk/brikk-house`
**Probe evidence date:** hazards JSON `extracted: 2026-07-13`; brikk pin.

---

## (a) Current released API surface + artifact/version status

### A.1 Public API surface of `brikk-sql-metadata`

The module is the intentionally featherweight, embeddable artifact (only dependency:
`kotlinx-serialization-json`). Two public accessor families:

**1. Function catalogs** (`FunctionCatalog.kt`):
- `enum FunctionKind { SCALAR, AGGREGATE, WINDOW, TABLE_VALUED, TABLE_GENERATING }`
- `enum NullPropagation { STRICT, SKIPS_NULLS, ALWAYS_NULLABLE, NEVER_NULL, UNKNOWN }`
- `data class SemanticProfile(nullPropagation, notes)`
- `data class FunctionOverload(argTypes, returnType, variadic, argNames)`
- `data class FunctionDef(name, kind, aliases, overloads, nativeKind, sinceVersion, profile)`
- `class FunctionCatalog(functions, grammarBuiltins)` with `get/contains/isKnown/isTableFunction/toJson`
- Generated per-dialect vals: `DORIS_FUNCTION_CATALOG`, `TRINO_FUNCTION_CATALOG`, `DUCKDB_FUNCTION_CATALOG`.

**2. Hazard registry** (`Hazards.kt` — the evidence source the connector needs):
- `enum HazardVerdict { IDENTICAL, DIVERGENT, CONDITIONALLY_EQUIVALENT, NO_EQUIVALENT, UNCLEAR }`
- `data class FunctionHazard(verdict: HazardVerdict, hazard: String?, areas: List<String>, provenance: String)`
- `object HazardRegistry { fun lookup(sourceDialect, targetDialect, functionName): FunctionHazard? }`

Wired pairs include `trino → doris` (backed by `GeneratedTrinoDorisHazards.kt`, "216 probe-verified
(trino, doris) pair verdicts").

### A.2 The critical shape gap in the released type (`FunctionHazard`)

`FunctionHazard` carries **only** `verdict`, `hazard`, `areas`, `provenance`. It does **NOT** carry
the target (Doris) function name or the source (Trino) function name. The target name survives only
as a **source-code comment** in the generated file, e.g.:

```
// [2] trino: 'contains' | doris: 'array_contains'    <-- comment only, not data
FunctionHazard(HazardVerdict.DIVERGENT, hazard = "...", areas = [...], provenance = "...")
```

`HazardRegistry.lookup("trino","doris","contains")` returns the `FunctionHazard` verdict/evidence
but the caller **cannot read the Doris target name (`array_contains`) back out of the API**. This
directly confirms PLAN G3's stated concern:

> "the current `HazardRegistry.lookup(...)` exposes verdict evidence but not the target name/shape."

**Verified TRUE.** The lookup is also keyed by the *source* name only (uppercased), so it is a
one-directional "is this source function safe?" gate, not a "source→target mapping" accessor.

Secondary consequence — **collapsed multi-target entries.** The registry's documented collision
policy keeps the WORST verdict for a key and, for target disambiguation, gives the caller nothing.
Example measured from the JSON: source `json_extract_scalar` has **two** target entries
(`json_unquote` and `get_json_string`, both `identical`). Through `lookup(...)` the connector sees a
single `FunctionHazard` and cannot tell which Doris target it pins to. (For the other five 6.2 rules
the source key maps to exactly one target, so only `json_extract_scalar` is currently ambiguous.)

### A.3 Artifact coordinates and RELEASE status — **BLOCKING**

- **Coordinate:** `dev.brikk.house:brikk-sql-metadata-jvm` (module.yaml `artifactId: brikk-sql-metadata`; JVM publication suffix `-jvm`).
- **Source-tree version today:** `0.7.0-SNAPSHOT` (in `publish.module-template.yaml`).
- **Publish config:** the Maven Central *release* path is **commented out**
  (`#mavenCentral: enabled`, `#signArtifacts: true`). Only `centralSnapshots` (SNAPSHOT repo) and
  `mavenLocal` are active. A `publish-release.sh <version>` exists but explicitly **rejects
  `-SNAPSHOT`** and has never been run to completion for this module (no non-SNAPSHOT metadata
  anywhere).
- **What actually exists in `~/.m2`:** ONLY `brikk-sql-metadata-jvm:0.1.0-SNAPSHOT` (both
  `maven-metadata-local` and `-centralSnapshots` list *only* `0.1.0-SNAPSHOT`). **No released
  (non-SNAPSHOT) artifact exists.**
- **Worse — the published `0.1.0-SNAPSHOT` jar predates the hazard registry.** Its class list
  contains only `FunctionCatalog*`, `FunctionDef*`, `FunctionOverload*`, `FunctionKind`, and the
  `Generated*FunctionCatalogKt` classes. It contains **NO `FunctionHazard`, `HazardVerdict`,
  `HazardRegistry`, or `GeneratedTrinoDorisHazardsKt`** (jar built 2026-07-12; hazards JSON extracted
  2026-07-13). The hazard API the connector depends on **is not in any published artifact at all**,
  only in the working tree.

Net: there is **no released artifact** to pin, and even the newest *snapshot* on disk does not
contain the hazard registry.

---

## (b) Hazards JSON schema + coverage of the §6.2 rules

### B.1 Schema of `brikk-sql/testResources/semantics/trino-doris-hazards.json`

Top-level object:
- `source_project: "brikk live-probe program"`
- `extracted: "2026-07-13"`
- `pairs: [...]` — **216 entries** (matches the "216 pairs claimed").

Each `pairs[]` entry has exactly six fields (verified as the union over all 216 entries):

| field | type | meaning |
|---|---|---|
| `trino` | string | source (Trino) function/construct name |
| `doris` | string | **target (Doris) function name** — present in JSON, dropped by `FunctionHazard` |
| `verdict` | string | `identical` / `divergent` / `conditionally-equivalent` / `no-equivalent` / `unclear` |
| `hazard` | string \| null | human-readable probe finding |
| `areas` | string[] | semantic areas (`array`, `boolean`, `json`, `string`, `regex`, `null`, `unicode`, `timezone`, ...) |
| `provenance` | string | pointer into the research report(s) |

Verdict distribution (216): `identical` 114, `conditionally-equivalent` 58, `divergent` 36,
`no-equivalent` 6, `unclear` 2.

**There is NO arity/argument-type/return-type field** in the hazards JSON. Arity/type constraints the
connector needs (PLAN §12 "brikk verdict granularity", §6.3 "accepted Trino argument types/arity")
must come from the separate `FunctionCatalog` overloads (or be owned by the connector), not from the
hazard entry. The JSON *does* carry the target name (`doris`) and provenance; the **released Kotlin
type is what loses the target name**, not the underlying evidence.

### B.2 Coverage of the §6.2 first high-value rules (quoted verdicts)

All rules the plan needs have an evidence entry. Quoted verbatim from the JSON:

**`contains(array,value)` → `array_contains(...)=1`** — target `array_contains`, verdict
**`divergent`**:
> "Doris live: 'contains' does NOT exist ('Can not found function contains'); the array-membership fn
> is array_contains([1,2,3],2) -> 1 (TINYINT). Trino contains(array,elem) returns BOOLEAN true. Both
> a NAME map (contains->array_contains) AND a boolean 0/1-vs-true/false type map are required."
> (areas: array, boolean; prov: REPORT-doris-differential-probe-2026-07-13.md#batch7-array)

**`arrays_overlap(a,b)` → `arrays_overlap(...)=1`** — target `arrays_overlap`, verdict
**`conditionally-equivalent`**:
> "Doris live: arrays_overlap([1,2],[2,3])=1 (overlap) and arrays_overlap([1,2],[3,4])=0 (disjoint).
> Value semantics match Trino, but Doris returns TINYINT 0/1 while Trino returns BOOLEAN — boolean
> type mapping required (Doris renders BOOLEAN as 0/1 over the MySQL protocol)."
> (areas: array, boolean; prov: ...#batch7-array)

**`array_position(array,value)`** — target `array_position`, verdict **`identical`**:
> "Doris live: array_position([10,20,30],20)=2 (1-based), and NOT-FOUND array_position([10,20,30],99)=0.
> Trino array_position is also 1-based and returns 0 when absent (matches). NOTE the DuckDB bridge
> differs on not-found (list_position returns NULL, not 0) — do not adjudicate via DuckDB here."
> (areas: array; prov: ...#batch7-array)

**JSON `json_contains`** — via source `json_array_contains` → target `json_contains`, verdict
**`identical`**:
> "GENERATOR FIXED (BUGS-doris-generator-mappings-2026-07-13 row 5): json_array_contains(json,val) now
> emits JSON_CONTAINS(json, val) (was the wrong `json MEMBER OF(val)`...). JSON_CONTAINS is
> result-identical (verified live = 1)."
> (areas: json; prov: ...#batch12-bucketb-trino; generator fixed in BUGS-...)

**JSON `json_extract`** — source `json_extract` → target `json_extract`, verdict **`identical`**:
> "Doris live == DuckDB live (json_extract('{"a":"x"}','$.a')='"x"' with QUOTES retained; ... missing
> path NULL). ... Trino==DuckDB==Doris live -> identical. Trino json_extract_scalar (unquoted) maps to
> Doris json_extract_string."
> (areas: json; prov: ...#batch8-json)

**JSON `json_unquote`** — source `json_extract_scalar` → target `json_unquote`, verdict
**`identical`** (NOTE: a second `identical` entry maps `json_extract_scalar`→`get_json_string`; both
collapse under the same registry key — see A.2):
> "GENERATOR FIXED (...row 6): json_extract_scalar(j,p) now emits JSON_UNQUOTE(JSON_EXTRACT(j,p)) (was
> bare JSON_EXTRACT, which kept JSON quotes on string scalars: '"hi"' vs Trino's 'hi'). The
> JSON_UNQUOTE wrap makes string scalars match (verified live = 'hi'); numeric scalars were already
> fine."
> (areas: json, string; prov: ...#batch12-bucketb-trino)

**`regexp_like` → Doris `regexp` predicate** — target `regexp`, verdict
**`conditionally-equivalent`**:
> "regexp_like(s,p) partial-match -> s REGEXP p; Trino BOOLEAN vs Doris TINYINT 1/0 — boolean type
> mapping only, same partial-match semantics."
> (areas: string, regex; prov: ...#batch12-bucketb-trino)

**Coverage conclusion:** every §6.2 rule has a pinnable evidence entry in the JSON with a clear
verdict and hazard text. The evidence content is sufficient; the released *API* is not (see (a)/(c)).

### B.3 `DorisGenerator.kt` (test/tooling-only) typed transforms

`brikk-sql/src/.../dialects/DorisGenerator.kt` (877 lines) is the full transpiler's Doris renderer —
**test/tooling-only** per G3, never a production dependency. Relevant typed target transforms present:

- `arrays_overlap` → `func("ARRAYS_OVERLAP", left, right)` (bare function; cannot cast array→boolean, line ~607-610).
- `json_array_contains` (`JSONArrayContains`) → `func("JSON_CONTAINS", left, right)` (line ~645).
- `json_extract_scalar` → `func("JSON_UNQUOTE", func("JSON_EXTRACT", thisArg, expr))` (line ~638).
- `regexp_like` (`RegexpLike`) → `renameFuncSql("REGEXP", ...)` (line ~659).
- `regexp_split` → `SPLIT_BY_REGEXP` (line ~665).

Confirmed **absent** (matching PLAN §6.3): the generator has **no `contains`→`array_contains` rule**
and **no `= 1` boolean-shape predicate wrapper** anywhere. `arrays_overlap` and `regexp_like` render
as bare function/rename, not as a `... = 1` predicate. So even as a test oracle, the generator does
not emit the connector's target shape for `contains` or the `=1` wrappers — the connector owns those,
as the plan already states.

---

## (c) Gap analysis vs the connector's needs

The connector (PLAN §4.1 `DorisPushdownEvidence`, §6.3) must, through a **released** API:
1. Pin each pushdown rule to an evidence entry: **verdict, areas, hazard text, provenance** — and
   also the **target Doris function name** so the rule↔evidence pin is checkable and a drift test can
   assert the connector's chosen target still matches brikk's probed target.

| Need | Released API today | Gap? |
|---|---|---|
| verdict | `FunctionHazard.verdict` | OK |
| areas | `FunctionHazard.areas` | OK |
| hazard text | `FunctionHazard.hazard` | OK |
| provenance | `FunctionHazard.provenance` | OK |
| **target Doris function name** | **absent from `FunctionHazard`** (comment only) | **GAP** |
| source function name (round-trip) | key only (implicit) | minor gap |
| disambiguate multi-target keys (`json_extract_scalar`→{json_unquote,get_json_string}) | not possible | **GAP** |
| arity / arg-types | not in hazards; in `FunctionCatalog.overloads` or connector-owned | acceptable (per plan, connector owns typed rules) |
| **a published artifact containing the hazard registry** | **none exists** | **BLOCKING GAP** |
| **a released (non-SNAPSHOT) artifact to pin** | **none exists** | **BLOCKING GAP** |

Two classes of gap:
- **API shape gap (real but small):** `FunctionHazard` cannot return the target function name, so the
  connector cannot fully pin a rule to "this exact Trino→Doris mapping" and cannot write a drift test
  that asserts the target name, through the released type. It can only assert the source-keyed verdict.
- **Release/packaging gap (blocking, independent of shape):** there is no released artifact, and the
  only snapshot on disk (`0.1.0-SNAPSHOT`) doesn't even contain the hazard classes. The source tree is
  at `0.7.0-SNAPSHOT` with the release path disabled.

---

## (d) Smallest additive API/release proposal (NOT implemented — requires user approval)

> Per AGENTS.md and G3/G12: this is a proposal only. Do not touch `brikk-house`, change its public
> API, or publish, without explicit user approval. Do NOT copy brikk data into this repo as a workaround.

**D.1 — Minimal additive API change (backward-compatible).**
Add the two names as **defaulted, nullable** fields on `FunctionHazard`, so existing JSON and existing
generated call-sites keep compiling and deserializing (matches the module's "additive-only, defaulted
fields" contract):

```kotlin
@Serializable
data class FunctionHazard(
    val verdict: HazardVerdict,
    val hazard: String? = null,
    val areas: List<String> = emptyList(),
    val provenance: String,
    val sourceName: String? = null,   // NEW — the source-side name (e.g. "contains")
    val targetName: String? = null,   // NEW — the probed target name (e.g. "array_contains")
)
```
Populate `sourceName`/`targetName` from the JSON `trino`/`doris` fields in
`tools/generate_hazards_registry.py`. Both names already exist in the source JSON, so this is a pure
generator/data-carry change, no new probing.

**D.2 — Optional (only if multi-target disambiguation is required now).**
`json_extract_scalar` is the sole §6.2 case where one source key has multiple targets. Options,
smallest first:
- (a) Do nothing in the API; the connector chooses `json_unquote` explicitly and the drift test
  asserts *some* released entry has `targetName == "json_unquote"` with `verdict == IDENTICAL`. This
  needs only D.1.
- (b) Add a `HazardRegistry.lookupAll(source, target, functionName): List<FunctionHazard>` returning
  every entry for the key (additive; existing `lookup` unchanged). Preferred only if the connector
  needs to enumerate alternative targets programmatically.

Recommend **D.1 + option (a)**; defer (b) unless a second multi-target rule appears.

**D.3 — Release/pin steps (the blocking part).**
1. Confirm the working tree's hazard registry (`GeneratedTrinoDorisHazards.kt`, 216 entries) plus D.1
   is what should ship.
2. Cut a **non-SNAPSHOT release** of `brikk-sql-metadata-jvm` (e.g. `0.7.0` or the agreed number) via
   `publish-release.sh`, which contains the hazard classes (the current `0.1.0-SNAPSHOT` does not).
3. Connector pins that exact released coordinate/version (never a SNAPSHOT — G3/G12).

Effort: small. No new live probing; D.1 is data-carry; D.3 is a release cut. The evidence itself is
already complete for the §6.2 rules.

---

## (e) Can P0's "mapping API released and pinned" exit criterion be met with the existing artifact?

**No — it is BLOCKED on the proposal.** Two independent blockers:

1. **No released artifact exists.** Only SNAPSHOTs, and the release path is disabled. G3/G12 forbid
   shipping the connector against a mutable SNAPSHOT, so "released and pinned" is unmet by definition.
2. **The hazard API is unpublished.** Even the newest artifact on disk (`0.1.0-SNAPSHOT`) contains
   only the function catalogs, not `HazardRegistry`/`FunctionHazard`/`GeneratedTrinoDorisHazards`. The
   evidence the connector pins to is not in any packaged jar yet.

Additionally, the released `FunctionHazard` type cannot expose the **target function name**, so even
after a plain release the connector's rule↔evidence pin (PLAN §6.3: "target function name and wrapper
shape") would be weaker than the plan requires — motivating the small additive D.1.

**Therefore:** P0's brikk exit criterion requires a `brikk-house` change (additive API D.1 + a
non-SNAPSHOT release D.3). This needs explicit user approval before any `brikk-house` edit or publish.
The connector must NOT copy the unpublished registry/JSON in as a schedule workaround (G12).

The good news: all six §6.2 rules already have complete, quotable live-probe evidence in the JSON; the
work is a small additive field + a release cut, not new research.
