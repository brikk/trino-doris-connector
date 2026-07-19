# trino-doris-connector

[![CI](https://github.com/brikk/trino-doris-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/brikk/trino-doris-connector/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A **read-only** [Trino](https://trino.io) connector for [Apache Doris](https://doris.apache.org).

Doris speaks the MySQL wire protocol, so Trino's generic MySQL connector can technically
connect — but it silently drops Doris's complex types (ARRAY, JSON edge cases, LARGEINT
overflows the whole query), leaves array predicates running in Trino where Doris's inverted
indexes can't help, and will happily execute writes against your Doris cluster. This connector
fixes all of that:

- **Correct types**, including native `ARRAY(T)` columns decoded exactly.
- **Typed predicate pushdown** rendered so Doris **inverted indexes stay eligible** — every
  pushed expression is verified for result equivalence (including NULL semantics) against a
  live Doris cluster.
- **Read-only by construction**: writes, DDL, and procedure escape hatches are denied inside
  the connector before any SQL reaches Doris.

Built and tested against **Apache Doris 4.1.3**, **Trino 483**, and MySQL Connector/J 9.7.0.

## Features

- One Doris statement per table scan — Doris runs its own distributed execution and streams
  results back; large results are streamed, not buffered in the Trino worker.
- Trino query cancellation kills the corresponding Doris query CLUSTER-WIDE (verified
  sub-second, including load-balanced / multi-FE deployments: the query is resolved by its
  id-comment in the cluster-wide processlist and killed by QueryId, which Doris forwards to
  the owning FE — `doris.cluster-scoped-cancel`, default on). Each remote query is tagged
  with the Trino query id for easy correlation in Doris logs.
- Dynamic catalogs: `CREATE CATALOG` / `DROP CATALOG` at runtime, with configuration validated
  loudly at create time.
- Doris session controls per catalog: `doris.query-timeout`, `doris.exec-mem-limit`,
  `doris.time-zone`, `doris.connect-timeout`, plus a per-query `query_timeout` session property.
- Table statistics for the Trino optimizer (`doris.statistics.enabled`, default on; per-query
  `statistics_enabled`): row counts and column NDV/nulls/size/ranges from Doris's statistics
  store. The connector only reads statistics — Doris auto-analyze (on by default) or your own
  `ANALYZE TABLE` keeps them fresh; missing statistics simply mean unknown estimates.

### Pushdown

Predicates and query shapes the connector pushes into Doris:

| Trino | Pushed to Doris as | Notes |
|---|---|---|
| Column comparisons and ranges (`=`, `<`, `BETWEEN`, `IN`, `IS NULL`) | native predicates | numeric, boolean, date, datetime, and decimal columns (incl. LARGEINT) |
| `contains(array_col, value)` | `array_contains(col, ?)` | rendered bare so the Doris **inverted index** can fire; numeric/decimal/date/datetime/boolean elements |
| `arrays_overlap(a, b)` | `arrays_overlap(...)` with a NULL-element guard | guard preserves Trino NULL semantics exactly |
| `array_position(a, x) <op> n` | `array_position(...)` comparisons | all six comparison operators, either orientation |
| `cardinality(array_col) <op> n` | `array_size(col)` comparisons | all six operators, `BETWEEN`, either orientation; NULL/empty/NULL-element semantics verified identical |
| `json_extract_scalar(json_col, '$.path') = 'text'` | `json_unquote(json_extract(col, '$.path')) = ?` | equality only, simple constant paths (`$.a.b`, `$.a[0]`); literals that could collide with Doris's non-scalar/number text renderings stay in Trino (see dev-docs/NOTES-p5-batch.md) |
| `date(dt_col)` / `CAST(dt_col AS DATE)` / `date_trunc('unit', dt_col)` | `CAST(col AS DATE)` / `date_trunc(col, 'unit')` | projection position, composes with GROUP BY + aggregates into one remote statement; units second/minute/hour/day/month/quarter/year (`week` excluded: year-0 truncation differs) |
| `coalesce` / `nullif` (exact types + varchar) | `coalesce(...)` / `nullif(...)` | projection, predicate, GROUP BY keys; compositions push as a unit |
| `year/month/day/hour/minute/second(dt_col)` | same-named functions | projection/GROUP BY (comparisons already unwrap to ranges upstream) |
| `lower(x)` / `upper(x)` | `lower(x)` / `upper(x)` | case mapping proven identical across a Unicode adversary battery |
| `length(x)` | `char_length(x)` | character count on both engines — never Doris `length()` (bytes) |
| `starts_with(col, 'p')` | `col LIKE 'p%'` (metacharacters escaped) | index-eligible prefix scan |
| `NOT` / `AND` / `OR` | composed remote predicates | over value-identical operands (e.g. `array_position` / `cardinality` comparisons) |
| `JOIN` (opt-in: `join-pushdown.enabled=true`) | `INNER/LEFT/RIGHT JOIN` as one remote statement | **off by default**; equality, `IS NOT DISTINCT FROM` (Doris `<=>`), and range conditions on non-text exact keys (numerics, decimal incl. LARGEINT, date, datetime, boolean); FULL OUTER and text/float keys stay in Trino; `join-pushdown.strategy=AUTOMATIC` (default) decides by table statistics, `EAGER` always pushes |
| `LIMIT n` | `LIMIT n` | |
| `ORDER BY ... LIMIT n` (TopN) | `ORDER BY ... NULLS FIRST/LAST LIMIT n` | non-text sort keys (plus VARCHAR keys in `BINARY`/`FULL` string modes); all four NULL orderings render natively |
| String predicates and `LIKE` | mode-dependent | see [String pushdown modes](#string-pushdown-modes) |
| `count(*)` / `count(col)` / `count(DISTINCT col)` | `count(...)` | any column type for plain counts; DISTINCT on non-text exact types only |
| `min` / `max` | `min(col)` / `max(col)` | numeric, decimal (incl. LARGEINT), date, datetime, boolean — not text (collation), not FLOAT/DOUBLE (Doris ranks NaN largest, Trino doesn't) |
| `sum([DISTINCT] col)` | `sum(...)` | DECIMAL(p≤18) only — every other sum type overflows *silently* in Doris where Trino raises an error, so those stay in Trino |
| `min_by(a, b)` / `max_by(a, b)` | same-named | exact-type keys; value column must be NOT NULL (Doris skips NULL-value rows where Trino keeps them — verified) |
| `any_value(x)` | `any_value(x)` | nondeterministic on both engines; NULL-handling verified identical |
| `approx_distinct(x)` | `approx_count_distinct(x)` | **opt-in** (`doris.approximate-pushdown`, default off): the HLL sketches differ, so estimates won't match local results |
| `avg(x)` | *not pushed* | Doris avg semantics diverge (scale truncation / lossy accumulation); write `sum(x)/count(x)` instead — both push and the division stays Trino-exact |
| `GROUP BY` (with the above) | `GROUP BY ...` | grouping keys restricted to the same non-text exact types; NULL grouping verified identical |

Deliberately **not** pushed (kept in Trino to guarantee correct results):

- `FLOAT`/`DOUBLE` equality — approximate types.
- `sum`/`avg` outside the table above — Doris wraps integer/decimal/LARGEINT sum overflow
  silently and truncates decimal averages where Trino rounds; these run in Trino so overflow
  still fails loudly and results stay Trino-exact.
- Anything whose Doris semantics differ from Trino's (e.g. `element_at`, `length`): unknown or
  unproven expressions always stay in Trino. Partially-pushable filters are split — the safe
  conjuncts go remote, the rest are evaluated in Trino.

### String pushdown modes

String comparison pushdown is configurable via the catalog property
`doris.string-pushdown.mode` and the session property `string_pushdown_mode` (session
overrides catalog in either direction):

| mode | VARCHAR/STRING predicates | CHAR predicates | string TopN | `LIKE` |
|---|---|---|---|---|
| `NULL_ONLY` | `IS [NOT] NULL` only | same | off | off |
| `GUARDED` (default) | proven-exact shapes (`=`, `<>`, `IN`, ranges) push **fully** — a `WHERE col = '...' LIMIT n` collapses into one remote scan; literals containing a 0x00 byte stay local (known hazard) | not pushed | off | prefix patterns (`LIKE 'foo%'`) ship a byte-range pre-filter (`col >= 'foo' AND col < 'fop'`); the exact `LIKE` is retained locally |
| `BINARY` | full pushdown, no retained filter | full pushdown | VARCHAR keys pushed | pushed |
| `FULL` | same as `BINARY` | same | same | same |

Background: a live probe of Doris 4.1.3 (`dev-docs/REPORT-string-comparison-probe-4.1.3.md`)
proved its string `=`/ranges/`IN`/`LIKE`/`ORDER BY` use pure byte (memcmp) semantics over
UTF-8 (`utf8mb4_0900_bin`) — identical to Trino's VARCHAR semantics, so `BINARY`/`FULL`
render identically on this version. The remaining hazards are handled per mode: CHAR
trailing-space data (Doris compares stored bytes, Trino compares trimmed values) keeps CHAR
out of `GUARDED` value pushdown and CHAR sort keys out of TopN in every mode; `LIKE`
patterns have backslashes doubled (Doris's default escape is `\`, Trino's no-escape `LIKE`
treats `\` literally), and explicit `ESCAPE` clauses pass through; `GUARDED` additionally
keeps predicates whose values contain a 0x00 byte local as defense-in-depth. `BINARY` is
the mode whose contract is probe-verified byte semantics; `FULL` is caller-asserted.

### Type mapping

| Doris | Trino |
|---|---|
| BOOLEAN, TINYINT, SMALLINT, INT, BIGINT | same |
| LARGEINT | DECIMAL(38,0) — out-of-range values fail loudly, never truncate |
| FLOAT / DOUBLE | REAL / DOUBLE |
| DECIMAL(p≤38, s) | DECIMAL(p, s) |
| DECIMAL(p>38, s) | VARCHAR (exact textual value) |
| DATE, DATETIME(0–6) | DATE, TIMESTAMP(0–6) |
| CHAR / VARCHAR / STRING | CHAR / VARCHAR |
| JSON, VARIANT | JSON |
| IPV4 / IPV6 | IPADDRESS |
| ARRAY&lt;T&gt; (numeric, decimal, boolean, date, datetime, IP — nesting supported) | ARRAY(T) |
| ARRAY of string types | not exposed natively (Doris's array wire text is ambiguous for strings; exposed as text with `unsupported-type-handling=CONVERT_TO_VARCHAR`) |
| MAP / STRUCT | text with `unsupported-type-handling=CONVERT_TO_VARCHAR`, otherwise hidden |
| BITMAP / HLL / AGG_STATE | hidden (opaque engine state) |

### Read-only

The connector is read-only end to end: `INSERT`, `DELETE`, `UPDATE`, `MERGE`, `TRUNCATE`,
`CREATE`/`ALTER`/`DROP`, comments, property changes, and the JDBC `system.execute` procedure
are all denied inside the connector — nothing mutating is ever sent to Doris. We also recommend
connecting with a SELECT-only Doris account (see below) so the database enforces the same
guarantee independently.

## Building

Prerequisites: JDK 25 (via [mise](https://mise.jdx.dev)) and Docker (for the live test cluster).

```bash
mise install        # JDK 25, from ./mise.toml
./compose/up.sh     # local Doris 4.1.3 cluster the tests run against
                    # (FE MySQL on 127.0.0.1:9130, HTTP on 8130, root / no password)
./gradlew build     # compile + lint + full test suite + plugin assembly + assembly verification
```

Tear the test cluster down with `./compose/up.sh --down`.

## Installing into Trino

The build assembles a standard Trino plugin directory (a directory of jars):

```bash
./gradlew pluginAssemble    # -> build/trino-plugin/trino-doris-<version>/
cp -r build/trino-plugin/trino-doris-<version> "$TRINO_HOME/plugin/"
```

Restart the coordinator and workers after copying.

### Releases (prebuilt plugin)

As an alternative to building from source, download the prebuilt plugin zip from the
[GitHub releases page](https://github.com/brikk/trino-doris-connector/releases) and unzip it
into your Trino plugin directory:

```bash
unzip trino-doris-<version>.zip -d "$TRINO_HOME/plugin/"   # -> $TRINO_HOME/plugin/trino-doris-<version>/
```

Then restart the coordinator and workers. Each release is cut from a `release-<version>`
branch and only published if the full live-test suite passes in CI.

## Configuring a catalog

### Static catalog

`etc/catalog/doris.properties`:

```properties
connector.name=doris
connection-url=jdbc:mysql://doris-fe-host:9030
connection-user=trino_ro
connection-password=***
```

### Dynamic catalog

With [dynamic catalog management](https://trino.io/docs/current/admin/properties-catalog.html)
enabled (`catalog.management=dynamic`):

```sql
CREATE CATALOG doris USING doris
WITH (
  "connection-url" = 'jdbc:mysql://doris-fe-host:9030',
  "connection-user" = 'trino_ro',
  "connection-password" = '***'
);
```

Configuration is validated at `CREATE CATALOG` time (a malformed URL fails the statement);
host reachability is checked lazily on first use. `DROP CATALOG doris;` removes it.

### Recommended: a SELECT-only Doris account

```sql
CREATE USER 'trino_ro' IDENTIFIED BY '***';
GRANT SELECT_PRIV ON *.* TO 'trino_ro';
```

## Try it: manual Trino smoke sandbox

[`compose/trino/`](./compose/trino) is a one-command **manual sandbox** — a single Trino 483
coordinator with this plugin installed and dynamic catalog management enabled, so you can log
in and add your own Doris server at runtime:

```sh
compose/trino/up.sh                          # assemble plugin (mise JDK 25), start Trino, print a cheat sheet
docker exec -it trino-doris-manual trino     # get a CLI, then CREATE CATALOG ... USING doris
compose/trino/down.sh                        # tear down
```

The plugin is mounted into `/usr/lib/trino/plugin/` and loaded via Trino's real
`ServiceLoader` plugin path (not the test classpath). See
[`compose/trino/README.md`](./compose/trino/README.md) for the cheat sheet, image-config
notes, and a worked end-to-end transcript (plugin load + predicate-pushdown proof).

## Development

Design notes, live-probe evidence reports, and the capability ledger behind every mapping and
pushdown decision live in [`dev-docs/`](./dev-docs).

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
