# NOTES — CI fixture bootstrap (p0_probe self-provisioning)

Context: CI run 29690535513 proved the workflow infra but failed en masse because every
live suite assumed the `p0_probe` database — created MANUALLY by the P0 probe harness on
the dev cluster, never by test code. Fixed by `DorisFixtures.ensureBaseFixtures()`.

## Replication methodology (how exactness was proven)

The original fixtures were replicated BEFORE the dev cluster was wiped:

1. `SHOW CREATE TABLE` for all of scalars / arrays / mapstruct / opaque / nums +
   `SHOW CREATE VIEW scalars_view`, plus full row dumps (JDBC getString) and row counts.
2. A scratch `p0_check` database was seeded from candidate statements, then two-way
   `EXCEPT`-diffed column-by-column against the live `p0_probe` (floats/doubles/json/
   variant/arrays/maps/structs compared via `CAST(... AS STRING)`; opaque via
   `bitmap_to_string`/`hll_cardinality`). Iterated until **0 differing rows in both
   directions on every table**, then the exact runtime SQL strings were ported verbatim
   into `DorisFixtures.PROVISION_STATEMENTS`.
3. Only then was the sanctioned wipe run: `./compose/up.sh --down && ./compose/up.sh`
   (`down -v` removes the fe-meta/be-storage volumes) followed by a full green build.

## Traps found while replicating (would silently diverge otherwise)

- **`c_double` rows 2/3 are ±DOUBLE_MAX, not ±Infinity**: the MySQL wire text for
  `±1.7976931348623157e308` reads "±Infinity" through JDBC `getString` — exactly the
  ledger's DOUBLE-Infinity READ hazard. Only `CAST(c_double AS STRING)` inside Doris
  reveals the true stored value. Row 6 holds the REAL `Infinity`/`NaN`/`-0.0` values.
- **`-0.0` literals lose the sign** on insert; `cast('-0.0' AS DOUBLE)` preserves it.
- **`c_dec76_10` extremes carry 65 integer nines**, not the type max of 66.
- **`scalars` has 8 rows with THREE id=6 rows** (one NaN/Infinity, two -0.0/-0.0
  duplicates) — the view-count assertions (`VALUES BIGINT '8'`) depend on it.
- **Session variables**: `enable_decimal256 = true` is required to CREATE the
  DECIMAL(76,10) column; **`enable_agg_state = true`** to CREATE the AGG_STATE column
  (both were set manually by the original probe — hidden assumptions no doc mentioned).
  They are session-scoped: `DorisTestCluster.executeAsRoot` runs all statements on ONE
  connection, so the SETs precede the DDL in the same statement list.
- **`nums` (0..1000999, 1,001,000 distinct)** seeds in ~250ms via
  `INSERT ... SELECT number FROM numbers('number' = '1001000')` — the `numbers()` TVF
  replaces the original cross-join generator.
- String/array/map adversaries (embedded quotes, `\`, tabs, newlines, emoji, CJK) were
  ported by capturing the RUNTIME SQL text of the proven statements — hand-transcribing
  escapes across Java/Kotlin/SQL layers is where fidelity dies.

## Wiring

- `DorisQueryRunner.Builder.build()` calls `ensureBaseFixtures()` first — covers every
  suite that constructs a runner (incl. dynamic-catalog and trino_ro variants).
- Suites whose OWN provisioning reads `p0_probe` BEFORE the runner exists call it
  explicitly: `TestDorisCancellation` (p1_cancel.big from nums),
  `TestDorisP2bInvertedIndexEvidence` (index fixture from nums), `TestDorisP3TopN`
  (>65535 proof reads nums).
- Idempotence: row-count fingerprint (scalars=8, arrays=4, mapstruct=4, opaque=2,
  nums=1001000, view=8); any mismatch (fresh cluster, partial crash state) triggers
  DROP + full rebuild; JVM-level `AtomicBoolean` makes repeat calls free.
- Already-self-provisioning state audited and left as-is: `trino_ro` user + `p1_ro_smoke`
  (TestDorisTrinoRoAccount), `p1_smoke`, `p1_readonly`, `p2_array`, `p2b_pushdown`,
  `p2b_index`, `p3_topn`, `p3_composition`, `p4_agg`, `p4_strings`, `p5_batch`.

## Fresh-cluster proof (2026-07-19, this host)

- `./compose/up.sh --down && ./compose/up.sh`: **13s** to healthy FE+BE from wiped volumes.
- Full `./gradlew build`: **6m29s**, 181 tests / 21 suites / 0 failures.
- `p0_probe provisioned in 1614ms` (single provisioning, all other suites fast-pathed).
