# NOTES — P3 slice (safe TopN + value-safe boolean composition)

Status: complete. `./gradlew :trino-doris:build` green. Builds on P2b (b56d12d); coded
against PLAN §6.5/§10-P3, SR K6 + drift watchlist.

## Implemented

1. **Safe TopN** (`DorisClient.supportsTopN/topNFunction/isTopNGuaranteed`):
   - Live probe answer: **Doris 4.1.3 natively supports `ORDER BY x ASC|DESC NULLS
     FIRST|LAST`** — all four Trino orderings render correctly (defaults are MySQL-style:
     ASC→NULLS FIRST, DESC→NULLS LAST). SR K6's ISNULL-prefix emulation is therefore
     unnecessary; the ledger's "keep the pattern, re-decide the rendering" resolved to the
     NATIVE syntax.
   - Sort keys allowed: TINYINT/SMALLINT/INT/BIGINT, DECIMAL (incl. LARGEINT-as-DECIMAL(38,0)),
     DATE, TIMESTAMP, BOOLEAN. Excluded with evidence: CHAR/VARCHAR (G5 collation unproven;
     SR K7 posture), REAL/DOUBLE (approximate; Trino orders NaN largest, Doris NaN placement
     unproven). One unsupported key in the list keeps the whole TopN local (tested).
   - `isTopNGuaranteed = true`: base-jdbc semantics — the engine drops its local TopN
     entirely; justified by live probes: `default_order_by_limit = -1` (bare ORDER BY does
     NOT truncate on this cluster — and we never emit bare ORDER BY anyway; the base-jdbc
     TopN function always renders WITH its LIMIT), and `ORDER BY n LIMIT 70000` (> 65535)
     returned exactly 70,000 ordered rows (closes the PLAN §9.2 truncation risk for every
     shape we push).
2. **Boolean composition over the VALUE-SAFE tier** (`DorisValueSafeRewriter`,
   `RewriteValueSafeNot`, `RewriteValueSafeLogical`): NOT/AND/OR (variadic) compose
   `array_position` comparisons only. Safety proof (both halves live-pinned):
   operands value-identical on both engines (P2b) + Doris NOT/AND/OR truth tables identical
   to Trino's standard 3VL (all cells probed: NOT N=N; T∧N=N, F∧N=F, N∧N=N; T∨N=T, F∨N=N,
   N∨N=N) ⇒ any composition of identical values is identical. The distinction is encoded
   STRUCTURALLY: composition rules rewrite children through a dedicated value-safe rewriter
   that does not contain `contains`/`arrays_overlap` (predicate-level-only rules), so
   `NOT contains(...)`, `contains(...) OR ...` etc. keep the whole conjunct local — tested,
   including the plain-contains-still-pushes contrast.

## Skipped (with reasons)

- **Standard scalar comparison rules ($equal/$lt/... via addStandardRules or the map DSL)**:
  column-vs-literal comparisons and IS [NOT] NULL on scalars are already covered EXACTLY by
  domain pushdown (P1a `ColumnMapping` controllers: FULL_PUSHDOWN for exact types, NULL-only
  for strings, DISABLE for approximates) — verified by the existing plan-shape tests
  (`testNumericDatePredicatesAreFullyPushedDown`, string IS NULL/IS NOT NULL fully pushed).
  What the standard rules would ADD is column-to-column comparison and arithmetic pushdown —
  new, unproven semantic surface (type coercion, overflow, decimal scale) ⇒ P3 remainder,
  per-family with live proof. Not added now ("don't add untested surface").
- **`is_null`/`is_not_null` expression rules**: covered by domain pushdown (above); skipped.
- **IS NULL over contains-style calls, NOT-composition of predicate-level rules**: stays
  local by design (correctness beats coverage; over-return counterfactuals proven in P2b).

## Test inventory (this slice)

- `TestDorisP3TopN` (6): four NULL orderings (plan + differential vs
  `topn_pushdown_enabled=false` + literal NULL-placement spot checks); all five other
  pushable key families; multi-key with unique tie-break; text/approx keys + mixed key list
  stay local and correct; >65535 limit exactness; audit-log remote shape
  (`ORDER BY `c_int` ASC NULLS LAST LIMIT 4`).
- `TestDorisP3Composition` (7): Trino + Doris 3VL pins; OR / nested AND-OR / NOT
  compositions pushed with differentials over NULL-array/NULL-element rows; remote shape
  `((array_position(`a_int`, 1) = 1) OR (array_position(`a_int`, 9) = 1))`; the
  never-compose battery for contains/arrays_overlap.

## Open items

Unchanged from NOTES-p2b (scalar families per-family proofs, constant-array arrays_overlap,
IPADDRESS parameter rendering, string-array superset pre-filter, brikk pin, MAP/STRUCT) plus:
projection pushdown and guarded conditional rewrites (P3 remainder), aggregates (P4).
