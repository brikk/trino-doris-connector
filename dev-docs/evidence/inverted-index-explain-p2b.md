# P2b evidence — ARRAY inverted-index EXPLAIN/profile observations (Doris 4.1.3)

Captured live 2026-07-19 against the stock compose cluster (`trino-doris-fe`, Doris
`4.1.3-rc02-7126cf65d96`), per PLAN §6.4: **correct emitted SQL + predicate-at-scan are the
P2b gates** (asserted repeatably by `TestDorisP2bInvertedIndexEvidence`); **optimizer index
selection and timings are the recorded observations below**, not flaky correctness gates.

## Fixture

`p2b_index.events` — 1,001,000 rows, `tags ARRAY<INT> NOT NULL` with an inverted index
created OUTSIDE the connector (direct JDBC as root; the connector stays read-only):

```sql
CREATE TABLE p2b_index.events (
    id BIGINT NOT NULL,
    tags ARRAY<INT> NOT NULL,
    INDEX idx_tags (tags) USING INVERTED
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1");
INSERT INTO p2b_index.events
SELECT n, ARRAY(CAST(n % 1000 AS INT), CAST((n + 7) % 1000 AS INT), CAST((n * 13) % 1000 AS INT))
FROM p0_probe.nums;  -- ~1.3s
```

Trino query: `SELECT id FROM doris.p2b_index.events WHERE contains(tags, 7)` → 3,003 rows.

## Generated remote SQL (audit-log capture, verbatim shape)

```
SELECT `id` FROM `p2b_index`.`events` WHERE ((array_contains(`tags`, 7))) /*trino_query_id=...*/
```

## Predicate-at-scan (EXPLAIN, the repeatable gate)

```
0:VOlapScanNode(74)
   TABLE: p2b_index.events(events), PREAGGREGATION: ON
   PREDICATES: (CAST(array_contains(tags[#1], 7) AS tinyint) = 1)
   ...
   nested columns:
     tags: predicate access paths: [tags]
```

EXPLAIN (plain or VERBOSE) does NOT reveal index selection on 4.1.3 — the plan shape is
identical with and without the index. The authoritative index-selection signal is the query
profile's `SegmentIterator` counters.

## Index selection (query profile, `enable_profile=true`) — THE decisive observation

| remote WHERE shape | RowsInvertedIndexFiltered | RowsExprPredFiltered / Input | InvertedIndexFilterTime |
|---|---|---|---|
| `array_contains(`tags`, 7)` (bare, the connector's rendering) | **997,997** | 0 / 0 | 250µs (query 58µs) |
| `(array_contains(`tags`, 7) = 1)` (the PLAN §6.2 wrapper shape) | **0** | 997,997 / 1,001,000 | 0 |
| `(array_contains(`tags`, 7)) AND (`id` > 500000)` (AND-composed bare) | **499,497** | 0 / 0 | — |

**Finding (drives the connector's rendering):** the `= 1` boolean-shape wrapper from PLAN
§6.2 makes the Nereids planner wrap the call in a CAST comparison that is evaluated as a
vectorized *expression predicate* — the inverted index is NOT consulted (0 rows
index-filtered; all 1M rows fed to the expression filter). The BARE truthy form is rewritten
into an index-eligible predicate and filters 997,997 of 1,001,000 rows in the index before
any column read. AND-composition with pushed domains preserves index use (the id-range
zonemap prunes first, the inverted index filters the remainder). The bare form is
predicate-level equivalent to the wrapped form (1/0/NULL all agree in WHERE context) and is
what `RewriteContains` emits; see `DorisPushdownEvidence.CONTAINS`.

## Timing (recorded, not a gate)

Full drain of the 3,003-row result over JDBC, warm, single BE, 1M rows:
- bare form (index): ~14–15 ms end-to-end (index filter itself ~250µs; the residual is
  row read + send + client).
- `enable_inverted_index_query=false` (forced expression filter): ~14–15 ms — at this scale
  the vectorized expression filter over 1M in-memory rows is as fast as the index path;
  the wall-clock benefit materializes with larger/wider tables and colder data. The
  scanned-work counters above (997,997 index-filtered vs 1M expression-filtered) are the
  scale-independent evidence.

## Non-indexed shapes (for completeness)

- `arrays_overlap(array_filter(x -> x IS NOT NULL, l), r)` (column×column) and
  `array_position(col, ?) <cmp> n` render at the scan as expression predicates; no inverted
  index applies to these shapes on 4.1.3 (no index candidates observed). They still benefit
  from pushdown by eliminating the row-protocol transfer of non-matching rows.

## Reproduce

```sh
# throwaway probes live under /tmp/opencode/p1b-probe (ProfileProbe*.java, IndexTiming.java);
# profile fetched via the FE HTTP API: curl -u root: http://127.0.0.1:8130/rest/v1/query_profile
# gates re-run repeatably by: ./gradlew :trino-doris:test --tests '*TestDorisP2bInvertedIndexEvidence'
```
