# trino-doris-connector

[![CI](https://github.com/brikk/trino-doris-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/brikk/trino-doris-connector/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A **read-only Trino connector for Apache Doris**, with Doris-aware types,
streaming reads and guarded query pushdown.

**Target stack:** Trino **483**, Apache Doris **4.1.5**, bundled MySQL Connector/J
**26.7.0**. Building requires **JDK 25**. Use a plugin built for the same Trino
version as every coordinator and worker; other version combinations are not qualified.

This is the Trino 483 compatibility branch of **0.4.0**, derived from the
published Trino 484 source with the shared fixes intact. It reuses the completed
Doris 4.1.5 qualification; only compilation/ABI/assembly/package checks are run
for this adaptation. See [branch compatibility notes](dev-docs/TRINO-483.md).

## Install

Build and verify the plugin directory from this checkout; Docker is not required
for this command:

```sh
mise install
mise exec -- ./gradlew prepareManualPlugin
PLUGIN_DIR="$(cat build/manual-plugin-path.txt)"
```

Stop Trino before installing. On **every coordinator and worker**, replace the
previous Doris plugin installation with the verified directory. The destination
below must not already exist:

```sh
cp -R "$PLUGIN_DIR" "$TRINO_HOME/plugin/doris"
```

Restart all nodes. Do not keep multiple versioned Doris plugin directories under
Trino's plugin root, copy over a running installation, or mix Trino/plugin versions.

## Configure a catalog

Doris databases are exposed as **Trino schemas**. Connect to the FE's MySQL
endpoint, normally port 9030, **without a database path**.

Create `etc/catalog/doris.properties`:

```properties
connector.name=doris
connection-url=jdbc:mysql://doris-fe-host:9030
connection-user=trino_ro
connection-password=YOUR_SECRET
```

Or, with Trino dynamic catalog management enabled:

```sql
CREATE CATALOG doris USING doris
WITH (
  "connection-url" = 'jdbc:mysql://doris-fe-host:9030',
  "connection-user" = 'trino_ro',
  "connection-password" = 'YOUR_SECRET'
);

SHOW SCHEMAS FROM doris;
SHOW TABLES FROM doris.analytics;
SELECT * FROM doris.analytics.events LIMIT 10;
```

Use a SELECT-only Doris account restricted to the required databases. For example,
an administrator can grant access to `analytics`:

```sql
CREATE USER 'trino_ro'@'%' IDENTIFIED BY 'YOUR_SECRET';
GRANT SELECT_PRIV ON internal.analytics.* TO 'trino_ro'@'%';
```

Restrict the account's allowed source hosts for production. The connector denies
writes and DDL itself, but database privileges are an independent safeguard.
`INSERT`, `UPDATE`, `DELETE`, `MERGE`, schema/table changes and the JDBC
`system.execute` escape hatch are unavailable. No arbitrary-query table function
is exposed.

### Connection URLs

- Multiple FEs: `jdbc:mysql://fe1:9030,fe2:9030,fe3:9030/`.
- Connector/J load-balancing and replication JDBC URL families are supported.
- DNS-SRV uses `dnsSrv=true` on an ordinary JDBC URL. Direct
  `jdbc:mysql+srv:` schemes are excluded by Trino's base URL constraint.
- Database paths and nonempty global/per-host `DBNAME` settings are rejected,
  including encoded and mixed-case forms.
- X Protocol (`mysqlx://`), nonempty `propertiesTransform` and `useConfigs`
  settings are unsupported. Catalog validation does not load transforms/profiles
  or resolve DNS. Host reachability is checked on first use.

Host selection/failover occurs when opening a read connection. That physical
connection is pinned for the attempt: there is **no in-flight HA statement
rerouting**. A retry opens a fresh connection.

## Settings

| Catalog property | Default | Meaning |
|---|---|---|
| `doris.connect-timeout` | `10s` | FE connect budget; 1–2147483647ms |
| `doris.query-timeout` | unset | Per-connection and per-scan Doris server timeout; 1–2147483647s |
| `doris.exec-mem-limit` | unset | Doris session `exec_mem_limit` |
| `doris.time-zone` | unset | Doris session time zone; never changed globally |
| `doris.string-pushdown.mode` | `GUARDED` | String-domain/comparison policy |
| `doris.statistics.enabled` | `true` | Expose Doris estimates to the optimizer |
| `doris.approximate-pushdown` | `false` | Permit approximate aggregates whose estimates differ between engines |
| `doris.cluster-scoped-cancel` | `true` | Best-effort cluster-wide QueryId cancellation helper |
| `join-pushdown.enabled` | `false` | Enable eligible INNER join pushdown |
| `join-pushdown.strategy` | `AUTOMATIC` | Statistics-based cost gate; `EAGER` bypasses cost, not safety checks |

Valid fractional timeout values round **up** to the respective unit. A query
timeout of `1.1s` becomes two seconds consistently at connection and scan setup.
Unset query timeout leaves the native session value unchanged. Connect/query
settings are not absolute wall-clock deadlines for DNS, credentials or custom
TLS/socket code.

Per-query overrides:

```sql
SET SESSION doris.query_timeout = '30s';
SET SESSION doris.statistics_enabled = false;
SET SESSION doris.string_pushdown_mode = 'GUARDED';
SET SESSION doris.approximate_pushdown = false;
```

Disabling statistics bypasses both shared and per-transaction statistics caches;
it does not expose warmed estimates or poison later enabled requests. Statistics
remain advisory: the connector reads them but never issues `ANALYZE`.

## Supported data

| Doris type | Trino representation |
|---|---|
| BOOLEAN, TINYINT, SMALLINT, INT, BIGINT | Corresponding boolean/integer type |
| LARGEINT | DECIMAL(38,0); values beyond Trino's range fail rather than truncate |
| DECIMAL(p≤38,s) | DECIMAL(p,s) |
| DECIMAL(p>38,s) | VARCHAR containing the decimal wire text |
| FLOAT / DOUBLE | REAL / DOUBLE via text transport |
| DATE / DATETIME(0–6) | DATE / zoneless TIMESTAMP(0–6) |
| CHAR / VARCHAR / STRING | CHAR / VARCHAR |
| JSON / VARIANT | JSON, with strict decoding |
| IPV4 / IPV6 | IPADDRESS |
| ARRAY of supported numeric, boolean, decimal, temporal or IP elements | ARRAY(T), including supported nesting |
| String-element arrays, MAP, STRUCT | Hidden by default; wire text with `unsupported-type-handling=CONVERT_TO_VARCHAR` |
| BITMAP, HLL, QUANTILE_STATE, AGG_STATE | Hidden under every unsupported-type policy |

Important limits:

- FLOAT/DOUBLE text transport supports NULL, infinities, NaN and signed zero,
  but is **not lossless binary transport** and cannot recover discarded precision.
- String-array wire text is ambiguous; it is not exposed as a native string array.
  Textualized complex/Decimal256 columns do not inherit native VARCHAR grouping,
  comparison or ordering semantics.
- SQL NULL and JSON null remain distinct. JSON/VARIANT use Trino 483's parsed
  UTF-8 Slice representation: object keys are sorted and duplicate keys collapse
  to the last value. Malformed input is rejected by Trino's 483 JSON parser.
  On Trino 483, `json_extract_scalar` equality is evaluated by Trino because its
  parser keeps the last duplicate key while Doris extracts the first.
- DATE/DATETIME predicates outside Doris's year 0000–9999 range stay local.
  DATETIME is a wall clock, not an instant; TIMESTAMP WITH TIME ZONE is not exposed.

## Query pushdown

Doris executes one remote statement per scan and distributes that work internally;
results stream back instead of buffering the full result in Trino.

| Operation | Admission |
|---|---|
| Comparisons, ranges, IN and null checks | Type-aware; string behavior follows the selected mode |
| ARRAY contains/overlap/position/size predicates | Supported typed elements and NULL-preserving guards |
| JSON scalar equality | Evaluated by Trino on 483; its parser keeps the last duplicate key while Doris extracts the first |
| Scalar expressions | Eligible coalesce/nullif, character length, prefix matching, temporal extracts and date projections |
| Aggregates | Eligible count/distinct, min/max, DECIMAL(p≤18) sum, any_value, guarded min_by/max_by; approximate aggregates are opt-in |
| GROUP BY, DISTINCT, HAVING, LIMIT and TopN | Eligible native/derived keys and type-specific ordering rules |
| Joins | Eligible INNER joins only, opt-in and cost-aware |

Unsupported shapes and unsafe compositions stay in Trino. LOWER/UPPER, AVG,
outer joins, FLOAT/DOUBLE value comparisons/orderings, and unsafe converted-text
operations are not pushed. `min_by`/`max_by` require a non-null payload; AVG is
not generally equivalent to manually writing `SUM/COUNT`. Conditional aggregate
masks and CASE/IF expressions are also retained by the engine.

### String pushdown modes

| Mode | String predicate/order behavior |
|---|---|
| `NULL_ONLY` | Direct string-column null checks; not a global switch disabling all string transformations |
| `GUARDED` | Conservative default: admitted exact predicates; LIKE-prefix prefilters retain the exact local filter; hazardous values stay local |
| `BINARY` | Broader binary-comparison contract, including eligible text TopN |
| `FULL` | Same renderings as BINARY under caller-asserted semantics |

Safety guards still apply in every mode. CHAR trailing-space behavior has separate
restrictions; LOWER/UPPER remain local. LIKE escapes push only for one non-NUL
ASCII character; unsupported escapes stay local. Larger IN sets can be compacted
to ranges while retaining an exact local filter.

## Cancellation and operations

Each scan has a unique leading statement token; Trino query IDs remain in SQL
comments for log correlation. The helper uses the matching Doris QueryId, never
Connector/J's load-balancer-unsafe numeric connection-ID kill. Closing one scan
does not authorize killing its siblings.

Helpers use short probes, close connections between retries and share bounded
worker/pending-request capacity. Their dispatch budget includes queue/retry time.
Cancellation is **best-effort**, with physical socket abort as an independent
fallback. Query visibility/kill permissions, credentials and network behavior
matter; a cancellation signal is not proof that all BE resources are already
reclaimed. Use the Doris server-side query timeout for a per-scan work limit.

## Local evaluation and development

The [manual Trino sandbox](compose/trino/README.md) runs this plugin in a local
Trino 483 container. It is unauthenticated and loopback-only by default; do not
share it with production credentials.

Build/test instructions, implementation invariants, qualification and the active
developer backlog are in [dev-docs](dev-docs/README.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
