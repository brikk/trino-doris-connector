# trino-doris-connector

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
- Trino query cancellation kills the corresponding Doris query (verified sub-second), and each
  remote query is tagged with the Trino query id for easy correlation in Doris logs.
- Dynamic catalogs: `CREATE CATALOG` / `DROP CATALOG` at runtime, with configuration validated
  loudly at create time.
- Doris session controls per catalog: `doris.query-timeout`, `doris.exec-mem-limit`,
  `doris.time-zone`, `doris.connect-timeout`, plus a per-query `query_timeout` session property.

### Pushdown

Predicates and query shapes the connector pushes into Doris:

| Trino | Pushed to Doris as | Notes |
|---|---|---|
| Column comparisons and ranges (`=`, `<`, `BETWEEN`, `IN`, `IS NULL`) | native predicates | numeric, boolean, date, datetime, and decimal columns (incl. LARGEINT) |
| `contains(array_col, value)` | `array_contains(col, ?)` | rendered bare so the Doris **inverted index** can fire; numeric/decimal/date/datetime/boolean elements |
| `arrays_overlap(a, b)` | `arrays_overlap(...)` with a NULL-element guard | guard preserves Trino NULL semantics exactly |
| `array_position(a, x) <op> n` | `array_position(...)` comparisons | all six comparison operators, either orientation |
| `NOT` / `AND` / `OR` | composed remote predicates | over value-identical operands (e.g. `array_position` comparisons) |
| `LIMIT n` | `LIMIT n` | |
| `ORDER BY ... LIMIT n` (TopN) | `ORDER BY ... NULLS FIRST/LAST LIMIT n` | non-text sort keys; all four NULL orderings render natively |

Deliberately **not** pushed (kept in Trino to guarantee correct results):

- String comparisons, `LIKE`, and string TopN — Doris collation differs from Trino; only
  `IS [NOT] NULL` is pushed for string columns.
- `FLOAT`/`DOUBLE` equality — approximate types.
- Anything whose Doris semantics differ from Trino's (e.g. `element_at`, `length`): unknown or
  unproven expressions always stay in Trino. Partially-pushable filters are split — the safe
  conjuncts go remote, the rest are evaluated in Trino.

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

## Development

Design notes, live-probe evidence reports, and the capability ledger behind every mapping and
pushdown decision live in [`dev-docs/`](./dev-docs).

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
