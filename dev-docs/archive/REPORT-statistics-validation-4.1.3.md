# Statistics validation: E-08 and E-09

Validated and fixed on 2026-09-05 against Doris 4.1.3 and Trino 483.
Both findings affect optimizer estimates; incorrect query result rows were not demonstrated.

## E-08: stale zero column statistics

The live validation analyzed an empty table, inserted `(id, v)` rows `(1, 42)` and `(2, 84)`,
and refreshed only the `id` column using `ANALYZE TABLE ... (id) WITH SYNC`.
The real statistics reader combined the fresh row count with stale zero-valued `v` statistics:

| Field | Reader before fix | Actual data |
|---|---|---|
| Table row count | 2 | 2 |
| v NDV | 0 | 2 |
| v null fraction | 0 | 0 |
| v data size | 0 | Two stored INT values |
| v range | unknown | 42 to 84 |

An all-NULL control with three rows correctly reported NDV 0 and null fraction 1.
Zero NDV is therefore not inherently invalid. Doris's unknown-statistics sentinel is also
not this case: SHOW omits unknown rows; a known empty-table statistic can contain zeros.

The fix retains a usable table row-count estimate but returns unknown column statistics if:

- The column-statistics count is zero despite a positive combined table row count; or
- NDV is zero without a null count equal to the combined row count.

The entire inconsistent column record is discarded, including its size, null fraction and
range. Merely making NDV unknown would leave other misleading estimates from the same snapshot.
Consistent all-NULL statistics and positive-NDV capping remain unchanged.

This is a consistency check, not a general freshness guarantee. Stale statistics that remain
internally consistent can still be used. Cache loading and auto-analyze timing make the stale
window nondeterministic, so the regression suite replays the captured input deterministically.

## E-09: wrong materialized-index statistics

The live validation created an aggregate-key table with `(k1, k2, v SUM)` rows:

```sql
(1, 1, 6), (1, 2, 7), (1, 3, 15), (2, 1, 5), (3, 1, 9)
```

Rollups on `(k1, v)` combine the first three values into 28. With several such rollups,
`SHOW COLUMN CACHED STATS` returned a rollup before the base index. The old first-row-wins
reader then reported the rollup's values for the base column:

| Field | Reader before fix | Base index |
|---|---|---|
| v NDV | 3 | 5 |
| v data size | 24 | 40 |
| v minimum | 5 | 5 |
| v maximum | 28 | 15 |

Doris source independently confirms the lack of a base-first ordering guarantee:
[`ShowColumnStatsCommand`](https://github.com/apache/doris/blob/4.1.3/fe/fe-core/src/main/java/org/apache/doris/nereids/trees/plans/commands/ShowColumnStatsCommand.java)
enumerates matching visible indices through
[`OlapTable.getMvColumnIndexIds`](https://github.com/apache/doris/blob/4.1.3/fe/fe-core/src/main/java/org/apache/doris/catalog/OlapTable.java).
SHOW emits separate index records; it does not aggregate them into base-table statistics.
The base index is named after the remote table, including after rename; `N/A` is not its name.

The reader now selects `index_name == remoteTable` before calculating row counts or column
estimates. If a column has no base-index record, it remains unknown: no fallback to a rollup
or unnamed row is permitted. Non-base counts cannot inflate the table's estimate either.

## Regression coverage

- `TestDorisTableStatisticsReader`: forces rollup-first and base-first ordering; ignores
  non-base counts, missing base records and unnamed rows; replays stale empty statistics
  with either fresh TABLE_ROWS or another fresh column; preserves all-NULL records and NDV caps.
  Four of its six tests failed before the implementation change.
- `TestDorisP5Statistics`: creates a live SUM rollup and verifies distinct base/rollup raw
  statistics, then checks base NDV/range through Trino SHOW STATS. A live all-NULL control
  verifies NDV 0 and null fraction 1 remain known. Trino SHOW STATS omits fixed-width data
  size, so that field is checked at the raw-source and reader-unit levels instead.

Verification: `mise exec -- ./gradlew build -PwithCancellation` completed successfully in
14m06s before the resource-related cluster shutdown: 313 passed, 5 multi-FE overlay skips,
zero failures. Detekt and plugin assembly verification passed. No runtime code changed
after that run.
