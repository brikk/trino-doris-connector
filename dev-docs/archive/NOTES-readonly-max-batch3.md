# NOTES — READ-ONLY-MAX batch 3: DISTINCT, HAVING, domain compaction (final batch)

Probes 2026-07-19 (scratch shape probe); pins in `TestDorisB3DistinctHaving`.

## Item 1 — DISTINCT

- `SELECT DISTINCT` lowers to an aggregation with NO aggregate functions (GROUP BY all
  columns) — the grouping-key gate decides pushability; there is no MarkDistinct in these
  plans at 483. Exact-key DISTINCT was ALREADY pushing (probed:
  `SELECT n, k FROM (... GROUP BY n, k) o`); composes with pushed WHERE; DISTINCT LIMIT
  pushes the LIMIT on top of the remote GROUP BY.
- **String DISTINCT is now safe and pushed**: grouping is EQUALITY-only, and string
  equality is byte-exact (P4 probe) with proven wire fidelity — the batch-3 change widens
  ONLY the grouping-key gate (`supportsAggregationPushdown`) to VARCHAR. Unicode
  adversaries (ß vs ss, ü vs U, NFC vs NFD é) group as distinct byte sequences on BOTH
  engines (differential-pinned, 6 distinct groups). NULL keys form one group on both.
  CHAR keys stay local (Doris groups stored bytes, Trino trimmed values); REAL/DOUBLE stay
  local (NaN/±0.0). Ordering-dependent surfaces (min/max args, TopN sort keys) deliberately
  do NOT inherit the widening — ordering remains mode-owned.
- **Windfall**: `count(DISTINCT text)` now collapses to ONE remote statement — the engine
  lowers it to `count(c) over (GROUP BY c)`, and the widened key gate lets that push.
  Distinctness stays Trino-exact (byte-distinct; 'apple'/'Apple' remain distinct — pinned).

## Item 2 — HAVING: verified, nothing to build

HAVING needs no connector code at 483: the engine plans it as a filter over the pushed
aggregation's synthetic output column, which applyFilter turns into a DOMAIN — the remote
statement carries it as an OUTER WHERE over the GROUP BY subquery:
`SELECT ... FROM (SELECT `n`, count(*) AS `_pfgnrtd_0` ... GROUP BY `n`) o WHERE `_pfgnrtd_0` > 100`
(wire-pinned). So HAVING PUSHES REMOTELY (not merely filtered-locally-on-tiny-rows).

## Item 3 — domain compaction

- The knob ALREADY EXISTS in base-jdbc: `domain-compaction-threshold` config + per-query
  `domain_compaction_threshold` session property, **default 256 at 483**. No redundant
  connector config added — documented in README instead.
- Behavior (wire-pinned): a small NON-ADJACENT integer IN pushes ENUMERATED
  (`IN (1,3,5)`; note ADJACENT integer values coalesce into ranges at the ENGINE level
  before any threshold applies — `IN (1,2,3,4,5)` becomes `>= 1 AND <= 5`); a 500-value IN
  compacts to a RANGE remotely with the exact filter RETAINED locally (differential-green);
  raising the session knob to 1000 makes the same IN push enumerated (`IN (0,2,4,...`).
  Default 256 kept: it balances remote SQL size vs selectivity, and per-query tuning is
  available where a selective giant IN matters.
- **Dynamic filters work and reach the remote SQL**: with join pushdown OFF (default), the
  dim side of a join flows into the probe-side scan as a domain (`WHERE `k` = 1` from
  `label = 'one'`, wire-pinned) — the join-off alternative is real.

## Wire shapes

`SELECT `n`, `k` FROM (SELECT `n`, `k` FROM t GROUP BY `n`, `k`) o` ·
`... GROUP BY `n`) o LIMIT 3` ·
`... GROUP BY `n`) o WHERE `_pfgnrtd_0` > 100` ·
`WHERE `n` IN (1,3,5)` / `WHERE (`id` >= 0 AND `id` <= 998)` (+retained) ·
probe-side `WHERE `k` = 1` (dynamic filter)
