# P0 spike — stock Trino 483 MySQL connector vs Doris 4.1.3

This is the baseline the custom `trino-doris` connector must beat. It points **stock
Trino 483's MySQL connector** at a live Doris 4.1.3 FE and records exactly where it
works, breaks, under-pushes, and endangers data. It is the evidence backing PLAN
sections 1, 3 (G1/G2/G8), 9.1, and 10 (P0), and it complements the wire/type probe in
`REPORT-protocol-type-probe-doris-4.1.3.md` (same cluster, same tree).

Every claim below is backed by raw output in
`evidence/stock-trino-smoke-raw.txt` (Trino CLI output + verbatim Doris FE audit-log
`Stmt=` text). Nothing here is inferred from MySQL-connector docs; it is observed
against the running cluster.

## Pinned versions

| Component | Exact value |
|---|---|
| Trino | `trinodb/trino:483` (`sha256:db58cc93e593a2706553745f276bb119c9810e69918be56ecde088ba7ccb0534`), CLI `Trino CLI 483`, server `483` |
| Trino connector | `connector.name=mysql` (stock MySQL connector, Base JDBC) |
| Bundled Connector/J | `mysql-connector-j-9.7.0 (Revision 0aade1f13bcc98faf7dda5c02e782481eb291f62)` (from the FE handshake comment in the audit log) |
| Doris FE/BE | `apache/doris:fe-4.1.3` / `be-4.1.3` (`fe` digest `sha256:3dd47644cd9fa8152028bdae449e77170ab0de004bd7a3fa311a204a106c26c7`), build `doris-4.1.3-rc02-7126cf65d96` |
| Doris MySQL-protocol version | `5.7.99` |
| Cluster | `trino-doris-fe` @ `172.30.81.10:9030` on docker net `trino-doris-dev_doris-net`; smoke container `trino-doris-smoke` (removed after run) attached to the same net, catalog IP `172.30.81.2` |

Catalog file mounted at `/etc/trino/catalog/doris.properties`:

```properties
connector.name=mysql
connection-url=jdbc:mysql://172.30.81.10:9030
connection-user=root
connection-password=
```

Relevant connector defaults it logged at startup: `unsupported-type-handling=IGNORE`,
`decimal-mapping=MAP_TO_NUMBER`, `mysql.jdbc.use-information-schema=true`,
`mysql.auto-reconnect=true`.

## Does it connect at all?

**Yes.** The stock MySQL connector starts against the Doris FE with no handshake or
version-check refusal. `SHOW SCHEMAS`, `SHOW TABLES`, `DESCRIBE`, `SELECT`, `EXPLAIN`,
aggregate/LIMIT pushdown, cancellation, and even writes all function. Doris's `5.7.99`
protocol string satisfies Connector/J's version checks, and the connector's MySQL
session setup (below) is accepted by Doris without error.

`SHOW SCHEMAS FROM doris` surfaces `__internal_schema`, `information_schema`, and each
Doris database as a Trino schema (G9's "Doris database = Trino schema" already holds for
the internal catalog). `SHOW TABLES FROM doris.p0_probe` correctly lists
`arrays, mapstruct, nums, opaque, scalars, scalars_view`, and `getTables` labels the view
as a `VIEW`.

## Type-surface table (Doris type → stock Trino type or error)

From `DESCRIBE doris.p0_probe.scalars/arrays/mapstruct/opaque` cross-referenced with
`information_schema.columns.COLUMN_TYPE` on the Doris side. **Silently dropped** means the
column does not appear in Trino at all (default `unsupported-type-handling=IGNORE`, no
per-query warning).

| Doris column type | Stock Trino surface | Correct? |
|---|---|---|
| `boolean` (`tinyint(1)`) | `tinyint` | **WRONG** — BOOLEAN indistinguishable from TINYINT; both are `tinyint`. Trino sees `0/1`, not a boolean. |
| `tinyint` | `tinyint` | ok (value ok; see BOOLEAN ambiguity) |
| `smallint` | `smallint` | ok |
| `int` | `integer` | ok |
| `bigint` | `bigint` | ok |
| `largeint` (128-bit signed) | `decimal(20,0)` | **WRONG/DANGEROUS** — DECIMAL(20,0) holds only 20 digits; Doris LARGEINT needs 39. Real 128-bit values **fail the whole query** with `Decimal overflow`. Even worse than PLAN's DECIMAL(38,0), which at least covers the ±1e38 mid-range. |
| `float` | `real` | ok |
| `double` | `double` | value ok for finite; **`Infinity`/`-Infinity` fail the whole query** (`Value '-∞' is outside of valid range for type java.lang.Double`). |
| `decimalv3(9,2)` | `decimal(9,2)` | ok, exact |
| `decimalv3(38,10)` | `decimal(38,10)` | ok, exact |
| `decimalv3(76,10)` (Decimal256) | `number` (surrogate) | **DEGRADED** — mapped to Trino's `MAP_TO_NUMBER` surrogate; loses trailing-scale fidelity (Doris `...890.1234567890` → Trino `...890.123456789`). |
| `date` | `date` | ok — even `0000-01-01` surfaces correctly (better than raw Connector/J `getDate`, which throws `YEAR`). |
| `datetime` (dt0) | `timestamp(0)` | ok, zoneless |
| `datetime(3)` | `timestamp(3)` | ok |
| `datetime(6)` | `timestamp(6)` | ok — microsecond precision preserved, `0000`/`9999` edges ok, no session-zone shift observed. |
| `char(10)` | `char(10)` | ok |
| `varchar(100)` | `varchar(100)` | ok |
| `string` (`text`) | `varchar(2147483643)` | ok (unbounded varchar) |
| `json` | `json` | ok — value round-trips incl. unicode/escapes |
| `ipv4` | **silently dropped** | missing column |
| `ipv6` | **silently dropped** | missing column |
| `variant` | **silently dropped** | missing column |
| `array<...>` (all element types) | **silently dropped** | every array column missing; `arrays` table shows only `id`. |
| `array<array<int>>` | **silently dropped** | missing |
| `map<string,int>` | **silently dropped** | missing |
| `struct<int,string>` | **silently dropped** | missing |
| `bitmap` | **silently dropped** | missing |
| `hll` | **silently dropped** | missing |
| `agg_state<...>` (`unknown`) | **silently dropped** | missing |

Net: on the `arrays`, `mapstruct`, and `opaque` tables the stock connector exposes
**only the `id` column** — every ARRAY/MAP/STRUCT/BITMAP/HLL/AGG_STATE column vanishes
with no warning. `scalars` loses `c_ipv4`, `c_ipv6`, `c_variant`.

### Type-surface highlights (what the custom connector must fix)

- **ARRAY is invisible.** `SELECT ... FROM arrays WHERE contains(a_int, 1)` fails with
  `Column 'a_int' cannot be resolved`. No array pushdown is even expressible. This is
  the single biggest gap and the core reason the connector exists (PLAN G4/G6/P2).
- **LARGEINT → `decimal(20,0)`** truncates the type and hard-fails on real 128-bit
  values (`Decimal overflow`). PLAN's DECIMAL(38,0) at least covers the mid-range.
- **BOOLEAN → `tinyint`**: cannot distinguish BOOLEAN from TINYINT; must be driven from
  `information_schema.columns.COLUMN_TYPE` (`boolean` vs `tinyint`), as the wire probe
  already prescribed.
- **Decimal256 → `number`** silently loses scale fidelity.
- **IPv4/IPv6/VARIANT silently dropped** even though they read cleanly as canonical text
  over the wire (per the wire probe) and could map to `IPADDRESS`/`VARCHAR`/`JSON`.
- **Silent drop, not fail-loud.** IGNORE means a query written against a dropped column
  errors as "column not found," and a `SELECT *` silently under-projects — the opposite
  of the AGENTS.md "fail loud over silently wrong" posture.

## Value-fidelity findings (scalar round-trips, edge rows)

Tested against `p0_probe.scalars` edge rows (min/max, NULL, unicode, `0000` dates,
`Infinity`, 128-bit extremes):

- **Integers** (`tinyint..bigint`): exact at min/max (`-9223372036854775808 ..
  9223372036854775807`), NULL preserved. Good.
- **LARGEINT**: `SELECT c_largeint` over the extreme rows **fails the entire query**
  (`Decimal overflow`) because the 128-bit values exceed `decimal(20,0)`. Only NULL rows
  return. This is a correctness cliff on a whole-column read.
- **DOUBLE**: finite values (incl. `±3.4e38`, `-0.0`) round-trip; **`Infinity`/`-Infinity`
  fail the whole query** (`Value '-∞' is outside of valid range`). One poisoned row kills
  the scan.
- **FLOAT**: `3.14`, `±3.402823E38` round-trip.
- **DATE**: `2021-06-15`, `0000-01-01`, `9999-12-31`, `2000-02-29` all correct. (The
  connector handles `0000-01-01` where raw Connector/J `getDate` throws.)
- **DATETIME(0/3/6)**: precision preserved to microseconds; `0000`/`9999` edges correct;
  no time-zone shift observed at default session zone (`+00:00`). Zoneless, as wanted.
- **DECIMAL(9,2)/(38,10)**: exact.
- **DECIMAL(76,10)** via `number` surrogate: **trailing-scale loss** — Doris row 1
  `123456789012345678901234567890.1234567890`, Trino `...890.123456789`.
- **CHAR/VARCHAR/STRING**: exact, incl. embedded commas, quotes, brackets, backslashes,
  emoji/CJK, tabs, newlines. Empty string distinct from NULL.
- **JSON**: server-normalized text round-trips verbatim, incl. escaped quotes and unicode
  escapes.
- **Aggregate fidelity**: `count(*)` = `1001000` matches Doris exactly.

## Pushdown ledger (what pushed / what stayed local)

Observed via Trino `EXPLAIN` (table handle) **and** confirmed against the Doris FE audit
log (`Stmt=`, `Client=172.30.81.2`). Every data scan is a **single remote statement**,
confirming **G2** (one JDBC statement per scan; Doris distributes it internally).

| Trino query shape | Pushed? | Verbatim remote SQL reaching Doris |
|---|---|---|
| (a) numeric range `WHERE c_int > 1000` | **PUSHED** | `` SELECT `id`, `c_int` FROM `p0_probe`.`scalars` WHERE `c_int` > 1000 `` |
| numeric range on `decimal(38,10)` | **PUSHED** (domain constraint, no local re-filter) | TableScan `constraint on [c_dec38_10]` |
| (b) varchar equality `WHERE c_varchar100 = 'a normal varchar'` | **PUSHED to Doris, re-checked in Trino** | `` SELECT `id`, `c_varchar100` FROM `p0_probe`.`scalars` WHERE `c_varchar100` = 'a normal varchar' `` — but Trino's plan is a `ScanFilterProject` that **keeps the `= 'a normal varchar'` filter locally too**. This is exactly the G5 collation hazard: the connector pushes a string equality to Doris as a superset filter without proving Doris collation matches Trino. |
| (c) `LIMIT 10` | **PUSHED** | `` SELECT `n` FROM `p0_probe`.`nums` LIMIT 10 `` |
| (d) `ORDER BY n LIMIT 10` (TopN) | **PUSHED** | `` SELECT `n` FROM `p0_probe`.`nums` ORDER BY ISNULL(`n`) ASC, `n` ASC LIMIT 10 `` — note the **MySQL-dialect `ISNULL()` null-ordering wrapper**; NULL-ordering semantics vs Doris are unproven (G5/§6.5 concern). |
| (e) `count(*)` | **PUSHED** | `` SELECT `_pfgnrtd_0` FROM (SELECT count(*) AS `_pfgnrtd_0` FROM `p0_probe`.`nums`) o `` |
| (e2) `sum(n)` | **PUSHED** | `` SELECT `_pfgnrtd_0` FROM (SELECT sum(`n`) AS `_pfgnrtd_0` FROM `p0_probe`.`nums`) o `` |
| (f) `contains(a_int, 1)` (array predicate) | **IMPOSSIBLE** | array column not exposed → `Column 'a_int' cannot be resolved`. No SQL generated. |

**Verbatim remote SQL for the numeric-range case (a):**
`` SELECT `id`, `c_int` FROM `p0_probe`.`scalars` WHERE `c_int` > 1000 ``

### Extra remote round-trips worth noting

- Each data scan is **preceded by a companion `SELECT * FROM <table>` metadata-probe
  statement** (`ReturnRows=1`) the connector issues to read `ResultSetMetaData`. So a
  logical scan is 1 data statement + 1 metadata probe, but the **data path itself is a
  single statement** (G2 intact). The custom connector should avoid the per-scan
  `SELECT *` probe if it drives types from `information_schema`.
- The connector also issues a large Connector/J handshake `SELECT @@session...` block and
  several `SET` statements per connection (below).

## Session / transaction findings

All session-setup statements the connector issued were **accepted by Doris** (every one
`State=OK`, no `SET TRANSACTION` rejection):

- `SET SESSION TRANSACTION READ ONLY` → **accepted** (Doris tolerates it; no error).
- `SET autocommit=1` → accepted.
- `SET SESSION time_zone='+00:00'` → accepted.
- `SET net_write_timeout=600` → accepted.
- `SET character_set_results = NULL` → accepted.
- `SET sql_mode='ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES'` → accepted.
- `SET sql_select_limit=1` / `=DEFAULT` → accepted (used to cap the metadata probe to 1
  row).

No warnings appeared in the Trino server log for these; the only startup WARN was an
unrelated transient `RemoteNodeMemory 503` during boot. There were **no per-query
warnings** about the dropped unsupported columns.

## Cancellation verdict

**Cancellation propagates: killing the Trino query kills the live Doris scan.**

- Heavy query: `SELECT count(DISTINCT a.n + b.n) FROM nums a, nums b` (1M×1M cartesian;
  the join runs in Trino, holding the FE scan open).
- Doris side showed a live scan `Id 454 'SELECT `n` FROM `p0_probe`.`nums`'` at Time=41s,
  `State=OK`.
- `CALL system.runtime.kill_query('20260719_014908_00038_vcjr4')` → Trino query went
  `FAILED` ("Query killed. Message: p0 cancel test").
- Within ~1s the Doris scan `Id 454` **disappeared** from
  `information_schema.processlist`. Connector/J's `Statement.cancel()` issues the KILL
  under the hood (consistent with the wire probe's ~18ms `KILL QUERY` timing).

This matches the PLAN §4.3 cancellation contract; the custom connector inherits this Base
JDBC behavior for free.

## Write-path danger findings (motivates G7)

**The stock MySQL connector does NOT refuse writes. It attempts every mutation, and DELETE
succeeded and destroyed data.** All probes were run against a private sandbox database
`p0_trino_smoke` (created and dropped by this spike; `p0_probe` was never mutated —
verified 8 rows intact afterward).

Verbatim mutation SQL that reached Doris (from the audit log):

- **DELETE — SUCCEEDED (`State=OK`):**
  `` DELETE FROM `p0_trino_smoke`.`t2` WHERE `id` = 1 ``
  Verified effect: unique table `t2` went from `{1:orig1, 2:orig2}` to `{2:orig2}` — the
  row was **permanently deleted through Trino**. Trino reported `DELETE: 1 row`.
- **DELETE on a DUP table — also executed** (`DELETE FROM ...t1 WHERE id=1`, `State=OK`,
  0 rows only because of the DUPLICATE key model, not a connector block).
- **UPDATE — reached Doris**, rejected only by Doris's model check
  (`UPDATE ...t1 SET v='updated' WHERE id=2` → "Only unique table could be updated"). The
  connector did not block it.
- **INSERT — reached Doris**, failed only on a sandbox-env replication default
  (`replication num is 3, available backend num is 1`), i.e. it generated and sent a real
  INSERT.
- **CREATE TABLE / CTAS — reached Doris**. As part of its write machinery the connector
  even issued `` CREATE TABLE `p0_trino_smoke`.`tmp_trino_...` AS SELECT * FROM ... WHERE
  0 = 1 ``. Explicit `CREATE TABLE (...)` failed only on DDL rendering (Trino emitted
  MySQL `tinytext`, which the Doris parser rejects) — again a rendering issue, not a
  refusal.
- **DROP TABLE — returned the success path.**

**Verdict: stock Trino would happily mutate a Doris cluster** (confirmed by an actual row
deletion). This is the concrete justification for **G7 defense in depth**: a
`ReadOnlyDorisClient` rejecting mutating methods, connector-level access control denying
write/DDL/procedure ops, not binding `query()`, auditing `system.execute`, **and** a
SELECT-only Doris account so a connector regression cannot write.

## Gaps the custom connector must close

1. **Expose ARRAY natively** (P2). Stock drops every array column, so `contains` /
   `arrays_overlap` index-aware pushdown — the whole value proposition — is impossible.
2. **Expose MAP/STRUCT/VARIANT/IPv4/IPv6** (or fail loud), not silently drop. At minimum
   IPv4/IPv6→`IPADDRESS`/VARCHAR, VARIANT→JSON.
3. **Fix LARGEINT.** Map to DECIMAL(38,0) and **fail loud** on the unrepresentable extreme
   range instead of stock's DECIMAL(20,0) that overflows on ordinary 128-bit values.
4. **Fix Decimal256 fidelity.** Stock's `number` surrogate silently loses scale; the
   connector must fail loud or use an exact VARCHAR policy (Trino's DECIMAL ceiling is 38).
5. **Distinguish BOOLEAN from TINYINT** via `information_schema.columns.COLUMN_TYPE`.
6. **Handle DOUBLE `Infinity`/`-Infinity`** without failing the whole scan (read via
   `getString` / fail-loud policy per row semantics, not a blanket query error).
7. **Type from `information_schema`, drop the per-scan `SELECT *` metadata probe.**
8. **Make string pushdown collation-safe (G5).** Stock pushes `varchar =` to Doris while
   also re-checking locally; the connector must prove Doris collation before pushing string
   predicates as fully-satisfied, and must not over/under-return.
9. **Prove TopN NULL ordering.** Stock emits MySQL `ORDER BY ISNULL(col) ASC, col ASC`;
   Doris NULL-ordering equivalence must be verified before the connector trusts pushed
   TopN.
10. **Enforce read-only (G7).** Stock connector executes INSERT/DELETE/UPDATE/CREATE/DROP
    against Doris and successfully deleted a row. The connector must refuse all mutation in
    code and access control, backed by a SELECT-only account.
11. **Surface unsupported types as warnings/fail-loud, not silent IGNORE**, per AGENTS.md
    "fail loud over silently wrong."

Positive baselines the custom connector inherits (already work with stock Base JDBC and
should be preserved): single-statement-per-scan (G2), numeric/date domain pushdown,
LIMIT/TopN/count/sum pushdown, streaming, and query cancellation propagating to Doris.

## Reproduce

```sh
# Doris 4.1.3 cluster already running: trino-doris-fe @ 172.30.81.10:9030 on
# docker net trino-doris-dev_doris-net (host port 9130).
mkdir -p /tmp/opencode/trino-smoke/catalog
cat > /tmp/opencode/trino-smoke/catalog/doris.properties <<'EOF'
connector.name=mysql
connection-url=jdbc:mysql://172.30.81.10:9030
connection-user=root
connection-password=
EOF
docker run -d --name trino-doris-smoke --network trino-doris-dev_doris-net \
  -v /tmp/opencode/trino-smoke/catalog:/etc/trino/catalog:ro trinodb/trino:483
# then: docker exec trino-doris-smoke trino --catalog doris --schema p0_probe --execute '...'
# remote SQL proof: docker exec trino-doris-fe grep Client=<trino-ip> \
#   /opt/apache-doris/fe/log/fe.audit.log | sed 's/.*|Stmt=//'
docker rm -f trino-doris-smoke   # cleanup; leave Doris running
```

Raw outputs: `evidence/stock-trino-smoke-raw.txt`.
