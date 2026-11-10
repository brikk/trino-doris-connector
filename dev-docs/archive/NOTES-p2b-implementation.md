# NOTES — P2b implementation (index-aware typed ARRAY predicate rules)

Status: P2b complete. `./gradlew :trino-doris:build` green. Builds on P2a (4725ee1); coded
against PLAN §6.1–6.4/§8/§10-P2, G3 (verdict gating), G6. The brikk metadata artifact remains
UNRELEASED/PARKED — evidence is carried as citations in [`DorisPushdownEvidence`] (source
name, target rendering, verdict, hazard line, provenance, `brikk artifact pin: PENDING
RELEASE`), structured so the pin can be added later without reworking rules. No brikk data
files copied; nothing in /home/jayson/DEV/brikk touched.

New classes: `DorisPushdownEvidence`, `DorisArrayPushdownSupport`, `RewriteContains`,
`RewriteArraysOverlap`, `RewriteArrayPositionComparison`; `DorisClient` gains the
`JdbcConnectorExpressionRewriterBuilder` wiring + `convertPredicate` + DEBUG accept/reject
logging with per-category rejection counters (PLAN §8 minimal). New tests:
`TestDorisP2bPushdown` (12), `TestDorisP2bInvertedIndexEvidence` (2). New evidence doc:
`dev-docs/evidence/inverted-index-explain-p2b.md`.

## Rules as implemented (exact guards)

All rules fire ONLY in predicate context (`convertPredicate` → top-level WHERE conjuncts).
There are deliberately NO composition rules ($not/$and/$or/$is_null) and no
`addStandardRules` — that is a correctness invariant, not an omission (see NULL findings).
Pushable element families (all guards enforced per argument): BOOLEAN, TINYINT, SMALLINT,
INT, BIGINT, LARGEINT (needle is a DECIMAL(38,0) constant — representable by construction,
PLAN §5 note), DECIMAL(p≤38), DATE, DATETIME(0..6). Excluded: FLOAT/DOUBLE (approximate;
spike §7.5), IPADDRESS (parameter rendering unproven — its scalar write function is the
varchar one over 16-byte slices, which would render garbage; open item), nested arrays.

1. **`contains(array(T), t)` → `(array_contains(`col`, ?))`** — guards: arg0 is a Variable
   bound to a native Doris `array<...>` column whose resolved element is pushable AND whose
   Trino type equals `array(elementType)`; arg1 is a NON-NULL `Constant` of exactly the
   element type (engine-inserted casts/expressions ⇒ stay local). Parameter bound via the
   element's own Doris type-handle → column-mapping write function (never `toWriteMapping`,
   which is read-only-denied).
2. **`arrays_overlap(array(T), array(T))` → `(arrays_overlap(array_filter(x -> x IS NOT
   NULL, `l`), `r`))`** — both args Variables, both pushable, identical element types. The
   null-strip wrapper is MANDATORY (see findings). Column×column only; constant-array
   arguments are an open item.
3. **`array_position(array(T), t) <cmp> n` (either orientation, operator flipped when the
   constant is on the left)** — position call must be type BIGINT with (Variable, non-NULL
   Constant) args under the same guards; bound must be a non-NULL INTEGER/BIGINT constant
   (rendered inline as a validated Long). All six operators pushed — justified by VALUE-level
   equivalence (below).

## NULL-semantics findings — live-proven truth tables (pinned in TestDorisP2bPushdown)

Needles/bounds are always non-NULL constants by guard; cells below are the reachable shapes.

### contains vs array_contains (needle t ≠ NULL)

| array | Trino `contains` | Doris `array_contains` | pushed bare form in WHERE |
|---|---|---|---|
| contains t | true | 1 | row kept ✓ |
| no t, no NULLs | false | 0 | row dropped ✓ |
| **no t, has NULL elements** | **NULL** | **0** | row dropped ✓ (both drop) |
| NULL array | NULL | NULL | row dropped ✓ |
| `[]` | false | 0 | row dropped ✓ |

⇒ **predicate-level equivalent, NOT value-level** (NULL vs 0 on the bolded row). Safe only
because the rendering can never escape top-level WHERE-conjunct context (no composition
rules). The counterfactual is tested: `NOT contains(a,5)` — Trino returns {1,5}, remote
`NOT array_contains` would return {1,3,5,6} (rows 3/6 over-returned) — which is exactly why
NOT/IS NULL shapes stay local.

### arrays_overlap (the trap that forced a guard wrapper)

| left × right | Trino | Doris bare | Doris `array_filter(x -> x IS NOT NULL, l)` wrapped |
|---|---|---|---|
| common non-NULL element | true | 1 | 1 ✓ |
| no overlap, no NULLs | false | 0 | 0 ✓ |
| no overlap, NULLs one side | NULL | 0 | 0 ✓ (both drop) |
| **NULL elements both sides, no common non-NULL** | **NULL** | **1 — OVER-RETURN** | **0 ✓ (both drop)** |
| overlap + NULLs present | true | 1 | 1 ✓ |
| either array NULL | NULL | NULL | NULL ✓ |
| empty | false | 0 | 0 ✓ |

⇒ Doris matches NULL↔NULL elements; the bare form would silently return rows Trino drops.
Stripping one side's NULLs (Doris never matches NULL to a value — proven) restores exact
predicate-level equivalence on every probed shape. G3 CONDITIONALLY_EQUIVALENT path.

### array_position (needle t ≠ NULL) — VALUE-level identical

| array | Trino | Doris |
|---|---|---|
| t at position k (NULL elements counted) | k | k ✓ |
| absent, no NULLs | 0 | 0 ✓ |
| absent, has NULL elements | 0 | 0 ✓ |
| NULL array | NULL | NULL ✓ |
| `[]` | 0 | 0 ✓ |

⇒ identical values ⇒ every comparison operator is exactly equivalent; all six pushed.
(NULL needles diverge — Trino NULL vs Doris 1 for `[NULL],NULL` — and are never pushed.)

## Deviation from PLAN §6.2's literal `= 1` shape (evidence-driven)

The plan's `array_contains(...) = 1` / `arrays_overlap(...) = 1` boolean normalization is
DROPPED in favor of the bare truthy form: query-profile evidence shows the `= 1` wrapper
makes Nereids evaluate the predicate as a vectorized expression (inverted index NOT used —
`RowsInvertedIndexFiltered: 0`, all 1M rows expression-filtered), while the bare form is
index-eligible (`RowsInvertedIndexFiltered: 997,997`, zero expression-filter input; still
true when AND-composed with pushed domains). Bare and wrapped are predicate-equivalent
(1/0/NULL agree in WHERE), and predicate context is the only context we emit. Details +
tables: `evidence/inverted-index-explain-p2b.md`. The `= 1` wrapper remains the right shape
if these renderings ever escape predicate context — they must not (invariant documented in
the rules KDoc and enforced by having no composition rules).

## Remote-SQL shapes (audit-log-asserted, verbatim fragments)

- `(array_contains(`a_int`, 1))`
- `(array_contains(`a_date`, '2021-06-15'))` (LocalDate parameter, client-prep interpolated)
- `(arrays_overlap(array_filter(x -> x IS NOT NULL, `a_int`), `b_int`))`
- `(array_position(`a_int`, 1) = 1)`

## Test inventory (all green)

- `TestDorisP2bPushdown` (12): four truth-table pin tests (Trino + Doris, incl. the guard
  wrapper battery); contains differential over all nine pushable families incl. NULL-tricky
  rows + direct Doris oracle triangulation; arrays_overlap differential (over-return trap row
  proven dropped); array_position differential over =, <>, >, >=, 0-if-absent and the flipped
  orientation; negatives (a_double, non-literal needle, NOT, IS NULL, cast-wrapped argument)
  each `isNotFullyPushedDown(FilterNode)` AND result-equivalent to no-pushdown; the
  NOT-counterfactual proof; audit-log remote-SQL shape assertions. Pushed cases assert
  `isFullyPushedDown` (which itself re-verifies results against the pushdown-disabled
  session) plus an explicit ordered differential using
  `SET SESSION complex_expression_pushdown = false` (the engine-level toggle; base-jdbc's
  `complex_expression_pushdown` connector session property also exists).
- `TestDorisP2bInvertedIndexEvidence` (2): PLAN §6.4 gates — emitted SQL is the
  index-accelerable bare form (audit log, `doesNotContain("= 1")`), correct 3,003-row result,
  `isFullyPushedDown`, and EXPLAIN-through-docker proof that the predicate reaches
  `VOlapScanNode PREDICATES`. Index selection/timing observations recorded in the evidence
  doc, not gated.

Fixtures: `p2b_pushdown` (recreated per run), `p2b_index` (persistent 1M-row inverted-index
fixture; index created via direct JDBC as root — connector stays read-only).

## Open items (P3+)

- Scalar expression families ($equal/$not_equal/... on columns, arithmetic, LIKE) — P3, one
  proven family at a time; NOT added now (the basic comparison DSL rules are not covered by
  existing domain-pushdown evidence for column-to-column shapes).
- `arrays_overlap(col, ARRAY[...literal...])` constant-array argument rendering (needs a
  Doris array-literal rendering + live proof).
- IPADDRESS element pushdown (needs a canonical-text parameter rendering; the current write
  function would bind the 16-byte slice as a string).
- String-array index acceleration via superset pre-filter (spike §7.6) — needs a
  retained-predicate mechanism convertPredicate does not offer; deferred by design.
- brikk pin: swap `PENDING RELEASE` citations in `DorisPushdownEvidence` for the released
  `brikk-sql-metadata` coordinates + registry drift tests (PARKED for user approval).
- MAP/STRUCT native decoding remains deferred.
