# NOTES — READ-ONLY-MAX batch 2: aggregate pushdown expansion

Probes 2026-07-19 (`/tmp/opencode/b1-probe/B2Doris.java` + engine shape probe); pins in
`TestDorisB2Aggregates`.

## Item 1 — conditional aggregation: ENGINE-DENIED at 483 (the key finding)

The SPI *carries* the plumbing (`AggregateFunction.getFilter()` exists; base-jdbc has
`hasFilter()` patterns) but it is UNREACHABLE for these shapes: during planning,
`FILTER (WHERE p)` — and `count_if`, which desugars to it — becomes an aggregation MASK
(`ImplementFilteredAggregations`), and `PushAggregationIntoTableScan` refuses to offer any
aggregation carrying a mask (483 source line 110: `allMatch(aggregation ->
aggregation.getMask().isEmpty())`). `applyAggregation`/`implementAggregation` is NEVER
invoked (verified by rejection-log silence for all four shapes). `sum(CASE WHEN p THEN a
END)` dies at the batch-1 wall instead: CASE has no ConnectorExpression form, so the
aggregate's ARGUMENT is untranslatable. Additionally moot: Doris 4.1.3 REJECTS the FILTER
syntax outright (probed), so a pushed rendering would have needed CASE-generation anyway.
DENY, pinned: remote statements carry no aggregate (wire-asserted), results correct via
local aggregation over the pushed scan. Re-check when Trino lifts the mask restriction.

## Item 2 — avg: deny SHARPENED; the manual decomposition is the pushable path

- The engine offers avg WHOLE (probed: `implementAggregation` received
  `avg(decimal(10,2))` and rejected it) — there is NO engine-side sum+count decomposition
  for connector pushdown at 483.
- The SPI maps one AggregateFunction to ONE remote expression (`JdbcExpression`), so a
  connector-side split is STRUCTURALLY impossible.
- The semantic deny reasons stand unchanged (P4 pins: Doris truncates decimal avg scale,
  double-accumulates bigint avg differently).
- The pushable path is the MANUAL rewrite: `sum(x), count(x)` both push (one remote
  statement, wire-asserted) and Trino-side division reproduces Trino's avg EXACTLY
  (HALF_UP, pinned) — documented in the README row as the user-facing guidance.

## Item 3 — min_by / max_by / any_value

| cell | Doris | Trino | consequence |
|---|---|---|---|
| NULL-KEY rows | ignored | ignored | identical ✓ |
| all-NULL-key / empty group | NULL | NULL | identical ✓ |
| **NULL VALUE at winning key** ({(NULL,1),(60,2)}) | **60 — skips NULL-value rows** | **NULL — keeps the payload** | DIVERGENT, data-dependent -> pushed ONLY when the VALUE column is declared NOT NULL (metadata guard makes the cell unreachable); nullable values stay local |
| ties | nondeterministic | nondeterministic | no contract to preserve; fixtures tie-free |

Key types: exact-ordering set (the key is compared). Value types: exact set + VARCHAR
(payload passthrough); CHAR/REAL/DOUBLE excluded (read-path hazards). Result reuses the
value column's type handle. `any_value`: both engines ignore NULL inputs ({NULL,7} ->
non-NULL on both, probed; all-NULL -> NULL), nondeterministic by contract -> differentials
assert MEMBERSHIP in the group, not equality.

## Item 4 — approx_distinct: opt-in only

`approx_distinct` -> `approx_count_distinct` behind `doris.approximate-pushdown` / session
`approximate_pushdown`, DEFAULT FALSE: both are HLL estimates with DIFFERENT sketches —
pushed and local answers legitimately differ for the same data, which violates the
exactness discipline unless the caller opts in. Type soundness proven (BIGINT; empty
input -> 0 on both). Off-by-default pinned (no remote aggregate without the flag).

## Composition

`SELECT year(dt) AS y, min_by(v, b), any_value(a), count(*) FROM t WHERE a > 0 GROUP BY 1`
= ONE remote statement (batch-1 projected grouping key + batch-2 aggregates + pushed
WHERE, wire-asserted).

## Remote SQL shapes

`min_by(`v_nn`, `b`)` · `any_value(`a_nn`)` · `approx_count_distinct(`a_nn`)` ·
`SELECT year(`dt_nn`) AS _pfgnrtd_0, min_by(...), any_value(...), count(*) ... WHERE (`a_nn` > 0) GROUP BY _pfgnrtd_0`
