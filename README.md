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

This development branch targets **Apache Doris 4.1.5**, **Trino 484**, and MySQL Connector/J 26.7.0.

Compose/CI and native FE/BE version checks lock **Doris 4.1.5** as the golden
validation baseline, with released metadata **0.21.0**. The
[release-safety qualification](dev-docs/REPORT-trino-484-release-safety.md) passed
**368 single-FE tests** with slow cancellation enabled and **five overlay checks
in a separate replay**, plus eleven resolution probes and an extracted-plugin
server smoke. Unicode case folding and outer joins stay local; HA read attempts
are physically pinned. These are staged results, not one no-skip combined run.
No release has been published; other pre-existing review findings remain separate.
The [latest cancellation/harness follow-up](dev-docs/REPORT-trino-484-cancellation-harness-safety.md)
passed 378 single-FE tests with slow cancellation enabled and five overlay checks
in a separate authorized replay, plus permission/identity safeguards and real-plugin
smoke. R-10 and R-15 are resolved; [remaining runtime/configuration triage](dev-docs/TRIAGE-trino-484-remaining-review.md)
keeps R-08/R-09/R-11/R-17 open. The earlier
[release-hardening batch](dev-docs/REPORT-trino-484-release-hardening.md) remains documented.
Historical [pre-fix 4.1.5 measurements](dev-docs/REPORT-trino-484-doris-4.1.5-lock.md),
[484 / 4.1.4.1 qualification](dev-docs/REPORT-trino-484-upgrade.md),
[Trino 483 / Doris upgrade qualification](dev-docs/REPORT-doris-4.1.4.1-upgrade.md)
and historical 4.1.3 probe evidence are preserved.

## Features

- One Doris statement per table scan — Doris runs its own distributed execution and streams
  results back; large results are streamed, not buffered in the Trino worker.
- Read connections retain URL host selection/failover at connect time, then pin one physical
  FE connection for the attempt. There is no in-flight HA statement rerouting; retries open
  fresh connections. Physical abort does not wait for the busy HA routing proxy.
- Scan cancellation resolves a unique statement-id comment in the cluster-wide processlist
  and kills by QueryId, which Doris forwards to the owning FE (`doris.cluster-scoped-cancel`,
  default on). This covers execution before the first result and early scan closure without
  killing sibling scans. Connector/J's connection-ID cancel is replaced; socket abort remains
  a fallback. Each remote statement also retains the Trino query id for correlation in logs.
- Cancellation helpers use a bounded 8-worker/64-request queue, a 15-second dispatch budget,
  2-second connect/read timeouts, and a post-connect abort watchdog. These limits do not alter
  normal scan connections; see [cancellation notes](dev-docs/NOTES-cancel-cluster-scoped.md) for details and limitations.
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
| IP comparisons and ranges (`=`, `IN`, `<`, `IS NULL`) | native `IPV4`/`IPV6` predicates | rendered in the target Doris dialect (IPV4 dotted-quad, IPV6 colon-hex); byte ordering identical on both engines; IPV4 columns push only IPv4-mapped literals (a real IPv6 literal can't equal an IPV4 value — stays in Trino) |
| `contains(ip_array, ip)` / `arrays_overlap(ip_array, ...)` | `array_contains` / `arrays_overlap` | `ARRAY<IPV4>` / `ARRAY<IPV6>`; overlap casts the literal elements to the IP type |
| `contains(array_col, value)` | `array_contains(col, ?)` | rendered bare so the Doris **inverted index** can fire; numeric/decimal/date/datetime/boolean elements |
| `arrays_overlap(a, b)` / `arrays_overlap(col, ARRAY[...])` | `arrays_overlap(...)` with a NULL-element guard | column×column and column×constant-literal (either orientation); guard preserves Trino NULL semantics exactly; empty/all-NULL literal stays in Trino |
| `array_position(a, x) <op> n` | `array_position(...)` comparisons | all six comparison operators, either orientation |
| `cardinality(array_col) <op> n` | `array_size(col)` comparisons | all six operators, `BETWEEN`, either orientation; NULL/empty/NULL-element semantics verified identical |
| `json_extract_scalar(json_col, '$.path') = 'text'` | `json_unquote(json_extract(col, '$.path')) = ?` | equality only, constant dotted-field paths (`$.a`, `$.a.b`); all array-subscript paths stay local because traversal differs on non-array values; literals that could collide with Doris's non-scalar/number text renderings also stay in Trino (see dev-docs/NOTES-p5-batch.md) |
| `date(dt_col)` / `CAST(dt_col AS DATE)` / `date_trunc('unit', dt_col)` | `CAST(col AS DATE)` / `date_trunc(col, 'unit')` | projection position, composes with GROUP BY + aggregates into one remote statement; units second/minute/hour/day/month/quarter/year (`week` excluded: year-0 truncation differs) |
| `coalesce` / `nullif` (exact types + varchar) | `coalesce(...)` / `nullif(...)` | projection, predicate, GROUP BY keys; compositions push as a unit |
| `year/month/day/hour/minute/second(dt_col)` | same-named functions | projection/GROUP BY (comparisons already unwrap to ranges upstream) |
| `lower(x)` / `upper(x)` | evaluated in Trino | Unicode mappings differ on Doris 4.1.5; local in every mode, including nested expressions |
| `length(x)` | `char_length(x)` | character count on both engines — never Doris `length()` (bytes) |
| `starts_with(col, 'p')` | `col LIKE 'p%'` (metacharacters escaped) | index-eligible prefix scan |
| `NOT` / `AND` / `OR` | composed remote predicates | over value-identical operands (e.g. `array_position` / `cardinality` comparisons) |
| `JOIN` (opt-in: `join-pushdown.enabled=true`) | eligible `INNER JOIN` as one remote statement | **off by default**; non-text exact keys; LEFT/RIGHT/FULL joins stay local to protect outer-join nullability; `AUTOMATIC` decides by statistics, `EAGER` bypasses only the cost gate, not safety restrictions |
| `LIMIT n` | `LIMIT n` | |
| `ORDER BY ... LIMIT n` (TopN) | `ORDER BY ... NULLS FIRST/LAST LIMIT n` | non-text sort keys, incl. IPADDRESS (byte-order identical) (plus native VARCHAR/STRING keys in `BINARY`/`FULL` string modes); all four NULL orderings render natively |
| String predicates and `LIKE` | mode-dependent | see [String pushdown modes](#string-pushdown-modes) |
| `count(*)` / `count(col)` / `count(DISTINCT col)` | `count(...)` | any column type for plain counts; DISTINCT on non-text exact types only |
| `min` / `max` | `min(col)` / `max(col)` | numeric, decimal (incl. LARGEINT), date, datetime, boolean — not text (collation), not FLOAT/DOUBLE (Doris ranks NaN largest, Trino doesn't) |
| `sum([DISTINCT] col)` | `sum(...)` | DECIMAL(p≤18) only — every other sum type overflows *silently* in Doris where Trino raises an error, so those stay in Trino |
| `min_by(a, b)` / `max_by(a, b)` | same-named | exact-type keys; value column must be NOT NULL (Doris skips NULL-value rows where Trino keeps them — verified) |
| `any_value(x)` | `any_value(x)` | nondeterministic on both engines; NULL-handling verified identical |
| `approx_distinct(x)` | `approx_count_distinct(x)` | **opt-in** (`doris.approximate-pushdown`, default off): the HLL sketches differ, so estimates won't match local results |
| `avg(x)` | *not pushed* | evaluated in Trino: Doris scale/accumulation semantics differ; `sum(x)/count(x)` is not a generally equivalent replacement |
| `GROUP BY` (with the above) | `GROUP BY ...` | exact-type and native VARCHAR/STRING keys (byte-exact grouping verified; CHAR and FLOAT/DOUBLE keys stay local); NULL grouping verified identical |
| `SELECT DISTINCT ...` | remote `GROUP BY` | same key rules as GROUP BY; composes with WHERE and LIMIT |
| `HAVING` | outer `WHERE` over the pushed GROUP BY subquery | verified end-to-end |

Deliberately **not** pushed (kept in Trino to guarantee correct results):

- `FLOAT`/`DOUBLE` equality — approximate types.
- DATE/TIMESTAMP parameters outside Doris's year `0000–9999` range, including array needles:
  these remain local because Doris can convert them to NULL and change query results.
- `sum`/`avg` outside the table above — Doris wraps integer/decimal/LARGEINT sum overflow
  silently and truncates decimal averages where Trino rounds; these run in Trino so overflow
  still fails loudly and results stay Trino-exact.
- Anything without a proven equivalent rewrite (e.g. `element_at`, `lower`, `upper`): unknown or
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
treats `\` literally). Explicit escapes pass through only for one non-NUL ASCII
character; unsupported escapes stay local in every mode. `GUARDED` additionally
keeps predicates whose values contain a 0x00 byte local as defense-in-depth. `BINARY` is
the mode whose contract is probe-verified byte semantics; `FULL` is caller-asserted.

### Pushdown coverage at a glance

Pushes (with live-proof evidence, see dev-docs): predicates/domains over exact types and
strings (tiered GUARDED), IN lists up to `domain-compaction-threshold` (default 256,
per-query `domain_compaction_threshold`; larger lists ship as ranges with the exact filter
retained), LIKE-prefix/`starts_with`, JSON equality, array predicates (`contains`,
`arrays_overlap`, `array_position`, `cardinality`), scalar expressions
(`coalesce`/`nullif`/temporal extraction/`length`/date projections),
aggregates (`count`/`min`/`max`/`sum(DECIMAL≤18)`/`count DISTINCT`/`min_by`/`max_by`/
`any_value`), GROUP BY / DISTINCT / HAVING / LIMIT / TopN, and opt-in cost-aware JOINs.
Dynamic filters from local joins also reach the remote scans.

Engine-limited at Trino 484 (cannot reach any connector): `CASE`/`IF` expressions and
conditional aggregation (`count_if`, `FILTER (WHERE ...)`, `sum(CASE ...)`) — these stay in
Trino by engine design, over a pushed scan. Deliberately not pushed: `avg` (Doris semantics
diverge; keep `avg(x)` for Trino's semantics), FLOAT/DOUBLE
equality/ordering, `approx_distinct` unless `doris.approximate-pushdown` is opted in.

Keep `avg(x)` rather than rewriting it for pushdown. For BIGINT values 1 and 2,
Trino returns `avg(x) = 1.5`, but `sum(x)/count(x) = 1` through integer division.
Decimal types, rounding and overflow add further differences, and the connector's
SUM eligibility is itself restricted.

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

FLOAT/DOUBLE reads support infinities, NaN, signed zero, and NULL. The sampled FLOAT extrema
now round-trip exactly on 4.1.4.1, unlike the reduced-precision text observed on 4.1.3.
These remain text mappings, not lossless binary transport; see the historical
[FLOAT probe](dev-docs/REPORT-float-special-values-4.1.3.md) and
[4.1.4.1 upgrade checks](dev-docs/REPORT-doris-4.1.4.1-upgrade.md).

VARCHAR renderings of Decimal256 or unsupported complex types do not inherit native string
grouping or ordering semantics. `GROUP BY`, `DISTINCT`, and TopN on those keys stay in Trino,
including under `BINARY`/`FULL` string modes. Native and derived string keys remain eligible
subject to the existing string-mode rules.

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
./compose/up.sh     # local Doris 4.1.5 cluster the tests run against
                    # (FE MySQL on 127.0.0.1:9130, HTTP on 8130, root / no password)
DORIS_TEST_ALLOW_MUTATION=1 ./gradlew build   # compile + lint + destructive disposable-cluster tests + verified assembly
```

Tear the test cluster down with `./compose/up.sh --down`.
Live tests deliberately DROP/rebuild fixture schemas and recreate test accounts. They require the explicit
`DORIS_TEST_ALLOW_MUTATION=1` opt-in **and** verified disposable Compose ownership,
network/port/image identity, and a matching native FE/BE roster and ClusterId.
Use `./compose/up.sh` to recreate older containers without the disposable label;
it preserves volumes. Matching ports or Doris versions alone never authorize
fixture setup. Pure unit tests such as
`./gradlew test --tests '*TestDorisTestSafety'` need neither opt-in nor Docker.
These permissions are test-harness-only; they do not enable connector writes.

The slow tagged cancellation suite requires `-PwithCancellation`; the multi-FE
checks require the optional overlay. They are not part of one default CI gate.
Overlay tests additionally require `DORIS_TEST_ALLOW_FAILOVER=1`, because they
stop/restart a follower. Without that flag they skip without probing ports;
with an explicit request, an unsafe/unavailable target fails rather than skips.
CI/release explicitly authorize fixture mutation, not failover, in their job environment.
Tooling guards run separately with `python3 -m unittest discover -s test/tooling -v`
and `python3 -m unittest discover -s test/qualification -v` (actual Gradle assembly/
release regressions, no live Doris needed).

## Installing into Trino

The build assembles a standard Trino plugin directory (a directory of jars):

```bash
./gradlew verifyPluginAssembly    # -> build/trino-plugin/trino-doris-<version>/
cp -r build/trino-plugin/trino-doris-<version> "$TRINO_HOME/plugin/"
```

Restart the coordinator and workers after copying. Build and deploy against the same
Trino version; a 483 plugin is not a 484-compatible release.
For upgrades, replace the previous Doris plugin directory on every node; do not
accumulate versioned Doris plugin directories under Trino's plugin root. Local
assembly synchronizes its owned output and verifies exact JAR inventory/bytes
against resolved inputs, including after dependency removal or upgrade.

### Releases (prebuilt plugin)

As an alternative to building from source, download a prebuilt plugin archive from the
[GitHub releases page](https://github.com/brikk/trino-doris-connector/releases). Releases are
named `trino-doris-v<trino>-<semver>` so the compatible Trino version is explicit (e.g.
`trino-doris-v483-0.1.0` targets Trino 483). Both `.tar.gz` and `.zip` are published, each with
a `.sha256` sidecar:

```bash
sha256sum -c trino-doris-v483-0.1.0.zip.sha256
unzip trino-doris-v483-0.1.0.zip -d "$TRINO_HOME/plugin/"   # -> $TRINO_HOME/plugin/trino-doris-v483-0.1.0/
```

Then restart the coordinator and workers. Each release is cut from a `release-<version>`
branch (or `release/<version>`) and only published after the default live-test
gate passes in CI. Version labels must be `<trino>-<major>.<minor>.<patch>` with
an optional non-SNAPSHOT SemVer prerelease; path components and build metadata
are rejected. The label must match the actual resolved Trino compile dependency,
not merely a user-supplied project version. These safeguards do not authorize a release.

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

[`compose/trino/`](./compose/trino) is a one-command **manual sandbox** — a single Trino 484
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
