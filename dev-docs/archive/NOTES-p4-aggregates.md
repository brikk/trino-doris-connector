# P4 — Verified aggregate pushdown (implementation notes)

Phase contract (PLAN §6.5, §10/P4): *"begin with COUNT, MIN/MAX on non-text, and exact SUM
types. Verify NULL, overflow, DECIMAL scale, and approximate-function behavior before each
rule."* Every family below was gated on a live Doris 4.1.3 probe (database `p4_agg`,
2026-07-19) BEFORE its rule was enabled. The raw gating facts are pinned by
`TestDorisP4AggregateProbes` (direct FE connection — they are properties of Doris, not of the
connector); the connector-level differentials, plan shapes, and remote-SQL shapes live in
`TestDorisP4Aggregates`.

## Mechanics

- `DorisClient.implementAggregation` runs an `AggregateFunctionRewriter` whose argument
  rewriter is ONLY `RewriteVariable` (quote a column reference): aggregate arguments must be
  bare columns; derived-expression arguments are structurally impossible. The predicate-side
  `ConnectorExpressionRewriter` (array rules + value-safe composition) is untouched.
- Synthesized result type handles carry Doris `COLUMN_TYPE` strings (`bigint`,
  `decimalv3(38, s)`) in `jdbcTypeName`, so pushed aggregate results flow through the exact
  same `DorisTypeMapping` read paths as ordinary columns (LARGEINT text-parse, DATETIME
  LocalDateTime, DATE text — nothing new on the wire).
- `DorisClient.supportsAggregationPushdown` restricts every grouping key to the
  exact-pushable key set (below) — a stricter form of base-jdbc's
  `preventTextualTypeAggregationPushdown` (which only blocks CHAR/VARCHAR).
- `ReadOnlyDorisClient` forwards `implementAggregation`/`supportsAggregationPushdown`
  (read-path probes, already classified in the reflection audit).
- Observability: accept/reject DEBUG logs mirror the predicate-pushdown categories
  (`aggregate pushdown accepted/rejected`, per-category rejection counters in
  `DorisAggregatePushdown.rejected`).

## The exact-pushable key type set

`TINYINT SMALLINT INT BIGINT DECIMAL(p<=38) [incl. LARGEINT] DATE DATETIME(0-6) BOOLEAN` —
deliberately the SAME set as the safe-TopN sort keys: min/max is an ordering question and
GROUP BY/DISTINCT an equality/grouping question, and this is the set whose ordering+equality
semantics are live-proven identical. Excluded: CHAR/VARCHAR (collation, G5), REAL/DOUBLE
(divergences below), JSON/IPADDRESS/ARRAY (no identity evidence).

## Family verdicts

| Family | Verdict | Live proof |
|---|---|---|
| `count(*)`, `count(col)` (any column type) | **PUSHED** | empty table → 0, all-NULL group → 0, NULL rows skipped — identical both engines; result type `bigint` |
| `count(DISTINCT col)`, exact key types | **PUSHED** | distinctness = grouping identity; result `bigint`; NULLs ignored both engines |
| `count(DISTINCT text)` | not pushed | collation/case distinctness hazard (same as GROUP BY text) |
| `min/max` on exact key types | **PUSHED** | Doris returns the argument type unchanged (view COLUMN_TYPE probe: BOOLEAN→`tinyint(1)`, LARGEINT→`largeint`, DATETIME(6)→`datetime(6)`); extremes proven: ±(10^38−1) LARGEINT, `0000-01-01`, `9999-12-31 23:59:59.999999`, near-±2^63 BIGINT |
| `min/max` on CHAR/VARCHAR | not pushed | Doris collation unproven (G5); Trino min over the fixture is `'Apple'` (codepoint order) — a remote min could differ |
| `min/max` on REAL/DOUBLE | not pushed | **DIVERGENT**: Doris `max({NaN, Infinity, …}) = NaN` (NaN largest); Trino 483 `max` uses `COMPARISON_UNORDERED_FIRST` (NaN smallest) → `Infinity`. ±0.0 ties are order-dependent on both engines and textually distinguishable |
| `sum(DECIMAL(p≤18, s))` (+ DISTINCT) | **PUSHED** | Doris result type `decimalv3(38, s)` == Trino `decimal(38, s)` (exact scale round-trip). Overflow unreachable: ≥10^20 max-magnitude addends needed vs the 2^63−1 row-count ceiling (10× margin; p=19 would need 10^19 — also impossible, 18 chosen for margin + short-decimal alignment) |
| `sum(DECIMAL(p>18, s))`, `sum(LARGEINT)` | not pushed | Doris decimal sums **wrap silently at Int128**, `check_overflow_for_decimal=true` notwithstanding: 2×max-DECIMALV3(38,2) → `-1402823669209384634633746074317682114.5`+NUL (unreadable even by Connector/J `getString`); `sum(LARGEINT)` of 4×2^126 → **0**. Trino throws "Decimal overflow" — kept local so it still does |
| `sum(BIGINT)` (and TINYINT..INT which output BIGINT) | not pushed | Doris wraps silently: `sum(MAX_BIGINT, 1) = -9223372036854775808`; Trino throws — kept local, `TestDorisP4Aggregates` pins the loud local failure AND the wrapped remote value it prevents |
| `sum(REAL/DOUBLE)` | not pushed | approximate; distributed summation order is engine- and run-dependent |
| `avg(*)` — every argument type | **not pushed (document-and-skip)** | three independent proofs below |

### Why avg is skipped entirely

1. **`avg(DECIMAL)` truncates**: Doris computes at scale `max(s, 4)` with TRUNCATION —
   `avg({0.0001, 0.0000})` (exact 0.00005, DECIMALV3(9,4)) → `0.0000`; Trino rounds HALF_UP →
   `0.0001`. For s≤3 the trunc-at-max(s,4)-then-CAST-half-up composition happens to equal
   half-up-at-s, but s≥4 is divergent → the family is unsound. (Upstream
   `ImplementAvgDecimal` accepts this class of divergence; this connector does not.)
2. **`avg(DECIMAL(38, s))` corrupts**: the scale-4 intermediate cannot hold 38 integer digits;
   a single-row `avg` of 10^38−1 returns a negative garbage value.
3. **`avg(BIGINT)` accumulation semantics differ**: Doris computes an EXACT wide integer sum
   then divides (`avg{2^53, 1, -2^53, 1}` → exactly 0.5; `avg(MAX_BIGINT, 1)` → the true
   mean even though `sum()` itself wraps). Trino 483 accumulates in **double**
   (`BigintAverageAggregations`: `state.setDouble(state.getDouble() + value)`) — order-
   dependent and lossy (a `1` added while 2^53 sits in the accumulator vanishes → 0.25).
   Note the direction: DORIS is the exact engine here, but the connector's contract is
   *Trino* semantics, and no rewrite makes a remote exact mean reproduce Trino's lossy
   double accumulation. (Initially assumed Trino throws on the internal sum overflow — the
   live test disproved that; `assertQueryFails` on `avg(bigint)` over MAX+1 SUCCEEDED and the
   source confirms double accumulation.) `avg(REAL/DOUBLE)`: approximate, as with sum.

## GROUP BY

Comes with aggregate pushdown; keys restricted to the exact-pushable set. NULL semantics
live-proven identical: NULL keys form one group (single- and multi-key), all-NULL groups
aggregate to `sum=NULL, count(col)=0, min/max=NULL`, empty tables produce zero grouped rows
and the standard global-aggregation row (`count=0`, others NULL). Unicode text keys
(`ü/U/ß/ss/straße`) stay local and group under Trino semantics.

## Composition

Aggregates compose with the existing pushdown surface: `count(*)` over a pushed domain
conjunct AND a pushed `contains(array, k)` renders as ONE remote statement
(`count(*) … WHERE (array_contains(…)) AND (`c_int` >= 1)`) — audit-log-shape asserted.

## Remote SQL shapes (from `fe.audit.log`, verbatim)

```
SELECT `g_int`, `_pfgnrtd_0`, `_pfgnrtd_1`, `_pfgnrtd_2`, `_pfgnrtd_3`, `_pfgnrtd_4`
FROM (SELECT `g_int`, count(*) AS `_pfgnrtd_0`, count(`c_int`) AS `_pfgnrtd_1`,
             sum(`c_dec`) AS `_pfgnrtd_2`, min(`c_date`) AS `_pfgnrtd_3`, max(`c_dt6`) AS `_pfgnrtd_4`
      FROM `p4_agg`.`t` GROUP BY `g_int`) o /*trino_query_id=20260719_130727_00196_upnvp*/

SELECT `_pfgnrtd_0`
FROM (SELECT count(*) AS `_pfgnrtd_0` FROM `p4_agg`.`t`
      WHERE `c_int` >= 1 AND ((array_contains(`c_arr`, 7)))) o /*trino_query_id=…*/
```
(synthetic `_pfgnrtd_N` result columns and the wrapping `… ) o` subquery are the base-jdbc
`prepareQuery` convention; statements asserted in `testRemoteAggregateSqlShapes` /
`testAggregateComposesWithPushedPredicates`.)

## Session control

Standard base-jdbc `aggregation_pushdown_enabled` catalog session property (default true) —
the differential suites run every family against `aggregation_pushdown_enabled=false` and a
direct-Doris oracle.

## Open items deferred from P4

- Doris MV-rewrite confirmation for pushed aggregate shapes (PLAN P4 bullet 3) and remote
  statistics (`SHOW COLUMN STATS`) — not part of the aggregate-correctness scope.
- `enable_decimal256` stays untouched (default false): flipping it changes Doris sum/avg
  result types wholesale and would need a fresh probe battery.
