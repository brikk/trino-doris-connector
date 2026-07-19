# NOTES — P5 remote statistics (Doris 4.1.3)

Live probes 2026-07-19 (throwaway probes `/tmp/opencode/p5-stats`; repeatable pins in
`TestDorisP5Statistics`; consumed by `DorisClient.getTableStatistics` via
`DorisTableStatisticsReader`).

## Probe findings: source -> field -> fidelity verdict

| source | field | verdict | consumed? |
|---|---|---|---|
| `information_schema.tables` | `TABLE_ROWS` | tablet-report-fed: EXACT for OLAP tables once the report lands; **reads 0 for ~1 report interval after a load** (the fresh-CI window); `-1` for views; no ANALYZE needed | YES (row count, max'd with analyze count; 0/-1 -> unknown) |
| `SHOW TABLE STATS` | `row_count` | same tablet-report feed as TABLE_ROWS (same staleness); blank fields when never analyzed | NO (redundant with TABLE_ROWS; one query fewer) |
| `SHOW COLUMN [CACHED] STATS` | `count` | EXACT at analyze time (SYNC returns with it populated); fresher than TABLE_ROWS right after load | YES (row-count max) |
| | `ndv` | sketch, can OVERSHOOT the row count (probed 1006 over 1000 rows FULL; 1000927 over 1001000 SAMPLE) | YES, capped at non-null row count |
| | `num_null` | exact | YES (nulls fraction) |
| | `data_size` / `avg_size_byte` | plausible totals | YES (`data_size` as dataSize estimate) |
| | `min`/`max` (integers, LARGEINT, DECIMAL incl. (76,10)) | plain numeric text, parses exactly | YES (range) |
| | `min`/`max` (FLOAT/DOUBLE) | HAZARD: renders `NaN`, `∞`, expanded notation | only when both ends parse FINITE (NaN/∞ -> dropped) |
| | `min`/`max` (DATE/DATETIME) | single-quoted text (`'2021-06-15 12:34:56.789012'`) | NO — parseable but the Trino stats-representation contract for temporal ranges is unproven; ndv/nulls still supplied |
| | `min`/`max` (VARCHAR/CHAR/IP/BOOLEAN) | quoted/`TRUE`/`FALSE` text | NO (ranges are numeric-only in this reader) |
| | `hot_values`, histograms | Doris 4.1.3 exposes hot values, no equi-height histogram in this surface | NO (nothing to consume) |
| `__internal_schema.column_statistics` | raw stats store | readable (even by trino_ro), but per-index rows need aggregation the SHOW command already does | NO (SHOW COLUMN CACHED STATS is the supported view of the same data) |

## What exists WITHOUT the user running ANALYZE

- `TABLE_ROWS` (tablet report): row counts for free, ~1 report interval stale.
- **Doris auto-analyze is ON by default** (`enable_auto_analyze=true`): the probe cluster
  had SYSTEM-triggered column stats on every fixture table within minutes of creation. So
  column statistics generally appear without user action — but the TIMING is
  nondeterministic, which is why the TESTS run `ANALYZE ... WITH SYNC` via root in setup
  (never via the connector) and why absent-stats degradation is a first-class tested path.
- `ANALYZE TABLE ... WITH SYNC` cost: 105ms for 1k rows, **53ms for the 1M-row nums** —
  trivial for test setup.
- `DROP STATS t` gives a deterministic never-analyzed state (used by the degradation test).

## Consumption rules (the reader's contract)

- Row count = max(TABLE_ROWS when >= 0, max per-column analyze `count`); **<= 0 -> unknown**
  (a stale 0 on a just-loaded table is indistinguishable from truly empty; an exact 0
  misleads the CBO far worse than unknown).
- ndv capped at non-null rows; nulls fraction = num_null / rowCount clamped to [0,1].
- Ranges: TINYINT/SMALLINT/INT/BIGINT/LARGEINT/DECIMAL/FLOAT/DOUBLE only, both ends must
  parse finite via BigDecimal (rejects NaN/`∞`/quoted text by construction).
- Fail SOFT everywhere: any statistics error -> `TableStatistics.empty()` + DEBUG log.
  Statistics are advisory; data paths keep their fail-loud discipline.
- Named relations only (synthetic join/aggregation handles -> unknown).
- Gating: `doris.statistics.enabled` (default true) + session `statistics_enabled`.

## Permissions (trino_ro, SELECT_PRIV on internal.*.*)

All four sources probed readable as `trino_ro`: information_schema.tables, SHOW TABLE
STATS, SHOW COLUMN [CACHED] STATS, and even `__internal_schema.column_statistics` — no
extra grants needed for the production SELECT-only account.

## Never issued by the connector

`ANALYZE` writes to Doris's statistics store — the connector is read-only end to end, so it
never issues it. Users rely on Doris auto-analyze (default on) or run ANALYZE themselves;
the README features section carries the one-liner.
