# NOTES — P6 date-of-datetime projection pushdown + GROUP BY composition

Motivating query: `GROUP BY date(event_at)` over a huge table -> ONE remote Doris
statement. Probes 2026-07-19 (`/tmp/opencode/p6-dates/DateProbe.java`); pins in
`TestDorisP6DateProjection`; registry evidence `DATE_OF_DATETIME` (IDENTICAL) +
`DATE_TRUNC` (CONDITIONALLY_EQUIVALENT: argument order swaps).

## Machinery

`ProjectFunctionRewriter` + `DorisClient.convertProjection` (the 483 postgres pattern):
a converted projection becomes a synthetic derived column in a `JdbcQueryRelationHandle`;
`applyAggregation` then groups on that synthetic column remotely. Grouping-key eligibility
comes free: synthetic columns carry DATE/TIMESTAMP types, already in
`DorisAggregatePushdown.isExactPushableKeyType`. Composition proven end-to-end:

```sql
SELECT `_pfgnrtd_0`, count(*), min(`v_1`) FROM
  (SELECT CAST(`dt6` AS DATE) AS `_pfgnrtd_0`, ... FROM `p6_dates`.`t` WHERE (`v` > 0) ...)
GROUP BY `_pfgnrtd_0` LIMIT 10  -- shape; WHERE pushes inside, LIMIT composes on top
```

## Unit-by-unit verdicts

| shape | verdict | evidence |
|---|---|---|
| `CAST(dt AS DATE)` == Trino `date(dt)` (desugars to the cast) | PUSHED — `CAST(col AS DATE)`; probe-identical to Doris `date()` on precisions 0/3/6 and the 0000-01-01/9999-12-31 edges | probe + differentials |
| `date_trunc` second/minute/hour/day/month/quarter/year | PUSHED — `date_trunc(col, 'unit')` (ARG ORDER SWAPS); quarter boundaries + year-0/9999 rows differential-identical; result preserves input precision on both engines | probe + unit matrix |
| `date_trunc('week', ...)` | **DENIED**. Both engines are Monday-start (Sunday 2026-07-19 -> 2026-07-13 probed, ISO-week-1 adversary identical) BUT at the calendar minimum Doris CLAMPS to `0000-01-01` where Trino truncates into year -1 (`-0001-12-27T00:00`) — data-dependent divergence for datetimes on 0000-01-01/02 (0000-01-01 is a Saturday), undetectable from the query. Pinned in `testEdgeDates`; re-admit iff the pin ever shows agreement. | probe + live Trino-vs-Doris pin |
| `date_trunc('millisecond', ...)` | DENIED by Doris ("time unit param only support year|quarter|month|week|day|hour|minute|second") — stays local. Trino itself rejects `microsecond`. | probe error pin |
| non-constant unit | never pushes (rule requires a constant from the allowlist) | negative test |
| `sum(BIGINT)` composed over the projection | NOT pushable (pre-existing P4 verdict: silent wrap) — the engine splits partial/final aggregation instead of full pushdown; count/min/max compose fully | test finding |

## Predicate position: came FREE from the engine

`WHERE date(dt) = DATE '2026-07-19'` / `CAST` / `date_trunc('day', ...)` comparisons are
unwrapped by Trino (`UnwrapCastInComparison` / `UnwrapDateTruncInComparison`) into RANGE
DOMAINS on the source datetime column BEFORE any connector involvement — fully pushed as
`` `dt6` >= ... AND `dt6` < ... `` with no projected expression in the remote SQL (pinned).
The projection rules are therefore NOT registered in predicate position; nothing to do.
