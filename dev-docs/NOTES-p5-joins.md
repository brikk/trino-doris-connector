# NOTES — P5 cost-aware join pushdown (Doris 4.1.3, Trino 483)

OFF by default (`join-pushdown.enabled=false` — the plan mandates opt-in until a
representative correctness/performance corpus exists). Live probes 2026-07-19
(`/tmp/opencode/p5-join/JoinProbe.java`; repeatable pins in `TestDorisP5JoinPushdown`).

## Probe verdicts

| shape | Doris | verdict |
|---|---|---|
| `NULL <=> NULL`, `NULL <=> 1`, `1 <=> 1`, `1 <=> 2` | `1, 0, 1, 0` | **identical to Trino IS NOT DISTINCT FROM** ⇒ IDENTICAL operator pushable via rendering override |
| `IS NOT DISTINCT FROM` syntax | REJECTED (parse error) | must render `<=>`; upstream's default rendering is the `≡` placeholder (unusable) ⇒ `DorisQueryBuilder.formatJoinCondition` override |
| INNER equality join, NULL keys | never match | identical ✓ |
| INNER equality join, duplicate keys (2×2) | 4 rows | identical multiplication ✓ |
| LEFT/RIGHT joins | NULL-extension for unmatched + NULL-key rows | identical ✓ |
| `<=>` as JOIN condition | NULL keys match each other | identical to Trino IS NOT DISTINCT FROM join ✓ |
| subquery-join shape (`(SELECT ...) l INNER JOIN (SELECT ...) r ON ...`) | supported | the exact legacy base-jdbc rendering works ✓ |

## What pushes (v1)

- **Join types**: INNER, LEFT, RIGHT. FULL OUTER excluded (unproven against nereids for
  this shape — conservative v1; plan allows).
- **Operators**: `=`, IS NOT DISTINCT FROM (→ `<=>`), and `<`, `<=`, `>`, `>=`, `<>`
  (rendered natively by base-jdbc; NULL keys drop identically — differential-pinned).
- **Key types**: the exact NON-TEXT set — TINYINT/SMALLINT/INT/BIGINT, DECIMAL (incl. the
  LARGEINT→DECIMAL(38,0) mapping; DECIMAL(76) maps VARCHAR and is excluded by the type
  gate), DATE, DATETIME(0–6), BOOLEAN.

## What stays local (with reasons)

- **FULL OUTER** — excluded join type (v1 scope).
- **CHAR/VARCHAR/STRING keys** — excluded in EVERY string pushdown mode (pinned: FULL mode
  does not unlock them). Byte semantics are P4-proven, but a remote join compares
  Doris-stored bytes while the local join compares wire values — that equivalence needs its
  own proof before text keys are enabled (documented future extension).
- **FLOAT/DOUBLE keys** — the wire text of extreme doubles reads "Infinity" (P0/P5-stats
  evidence): Trino-side and Doris-side comparisons can see DIFFERENT values, so a pushed
  join could return different rows than the local join it replaces.
- **IPADDRESS/JSON/complex types** — not comparable or unproven.
- **INNER `IS NOT DISTINCT FROM` / INNER non-equi** — engine behavior, not connector: Trino
  plans these as cross-join + filter, which never reaches `applyJoin` (LEFT/RIGHT shapes
  keep the condition in the join and push).

## Wiring findings (the traps)

1. **`JdbcJoinPushdownSupportModule` silently flips `join-pushdown.enabled` to TRUE**
   (bindConfigDefaults inside the module) — installing it as-is violates the OFF-by-default
   contract (caught by the suite's off-by-default test). The module's two useful bindings
   (`JdbcJoinPushdownConfig` + strategy session properties) are bound directly instead,
   without the default flip.
2. **Complex join pushdown (483 default ON) has NO legacy fallback**: `applyJoin` requires
   every condition to convert via `convertPredicate`; our rewriter deliberately has no
   variable-to-variable comparison rules, so leaving it on makes joins silently unpushable.
   Forced OFF via `bindConfigDefaults(JdbcMetadataConfig){ complexJoinPushdownEnabled=false }`
   → joins flow through the LEGACY path whose per-condition `JdbcJoinCondition` guard
   (`isSupportedJoinCondition`) is exactly the typed gate v1 wants.
3. **Upstream IDENTICAL rendering is `≡`** — a deliberate placeholder. `DorisQueryBuilder`
   overrides `formatJoinCondition` for IDENTICAL only; everything else uses the upstream
   rendering.

## AUTOMATIC strategy (cost-aware) — observed behavior

`JdbcJoinPushdownUtil.implementJoinCostAware` consults engine estimates fed by
`getTableStatistics` (P5 statistics reader):
- Both sides ANALYZE'd (sizes known, small) → **pushed** under AUTOMATIC.
- `DROP STATS` on both sides (unknown sizes) → **stays local** under AUTOMATIC while the
  IDENTICAL shape pushes under EAGER — the pinned proof that the statistics-fed cost gate
  is what decides, and that the fail-soft stats path degrades to the conservative decision.
- Knobs: `join-pushdown.strategy` (AUTOMATIC default when enabled), session
  `join_pushdown_strategy`, `join_pushdown_automatic_max_table_size`,
  `join_pushdown_automatic_max_join_to_tables_ratio` (upstream defaults).

## Snapshot posture

A pushed join executes as ONE remote statement (audit-log-pinned single `Stmt` containing
both relations) = one Doris MVCC snapshot — strictly stronger than the two independent
scans + local join it replaces. Shape:

```sql
SELECT `lv_1`, `rv_3` FROM (
  SELECT l.`k` AS `k_0`, l.`lv` AS `lv_1`, r.`k` AS `k_2`, r.`rv` AS `rv_3`
  FROM (SELECT `k`, `lv` FROM `p5_join`.`l` WHERE `id` > 1) l
  INNER JOIN (SELECT `k`, `rv` FROM `p5_join`.`r`) r ON l.`k` = r.`k`
) o /*trino_query_id=.../
```

(predicates push INSIDE the join sides; aggregates compose ON TOP — both audit-pinned).

## Read-only audit

`implementJoin`/`legacyImplementJoin`/`isSupportedJoinCondition` are read-path methods,
already in the reflection-audit allow-list — `TestReadOnlyDorisClient` green, unchanged.

## Test surface

`TestDorisP5JoinPushdown` (11): off-by-default pin; INNER/LEFT/RIGHT equality differentials
+ exact NULL/duplicate-key cardinality pins; IS NOT DISTINCT FROM (LEFT shape, `<=>` wire
assertion); five non-equi operators (LEFT shape); all six eligible key types; empty-side
differentials; excluded shapes (FULL OUTER, text keys incl. under FULL string mode, double
keys) local + correct; single-remote-statement snapshot pin; aggregate-over-join +
predicate-inside-join composition; AUTOMATIC-vs-stats behavior. LARGEINT fixture caution:
join fixtures must use in-range (≤38-digit) LARGEINT values — the 2^127−1 extreme fails
loud on READ by type contract (that's the P1a smoke's pin, not a join bug).
