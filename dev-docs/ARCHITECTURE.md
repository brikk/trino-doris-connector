# Architecture and invariants

## Wiring and read-only boundaries

`DorisPlugin` uses Trino's Base JDBC infrastructure through `DorisClientModule`.
`DorisReadOnlyAccessControl` rejects SQL-user mutations, and the `@ForBaseJdbc`
client is `ReadOnlyDorisClient` forwarding only permitted operations to
`DorisClient`. Raw statement/procedure entry points that can mutate Doris are
denied. Arbitrary-query table functions are not registered. A SELECT-only account
is an independent server-side defense, not a replacement for these layers.

Reflection-based tests classify the JDBC surface on the pinned Trino version;
upgrades must review added methods and entry points. The scan statement wrapper
protects Trino's no-argument `executeQuery()` seam, not arbitrary vendor-unwrapped
JDBC usage by trusted Java code.

## Types and value provenance

- Authoritative `information_schema.columns.COLUMN_TYPE` drives mappings; JDBC
  type codes alone cannot distinguish Doris BOOLEAN, LARGEINT and complex types.
- Exact integer/decimal paths check ranges. LARGEINT values outside DECIMAL(38,0)
  fail rather than truncate; Decimal256 is exposed as text, not a numeric surrogate.
- DATETIME maps to zoneless TIMESTAMP(0–6) and uses LocalDateTime/text, never a
  zone-shifting `getTimestamp` path. Native temporal limits apply to original
  domain endpoints, constants and array needles before lossy compaction.
- Supported arrays use strict wire decoding. String-family array leaves are
  ambiguous and not decoded by guessing; unsupported complex text is a rendering,
  not a native comparable VARCHAR value. Nested arrays inherit leaf restrictions.
- FLOAT/DOUBLE text transport cannot recover discarded wire precision. JSON
  decoding stays strict; SQL NULL and JSON null are distinct.

Native type, wire rendering, nullability and physical column identity must survive
planner composition. A Trino type alone is not remote-semantic evidence. Resolve
assignments to physical column names; do not use planner symbol names as SQL identifiers.
Malformed metadata/wire grammar policy remains an explicit [backlog](BACKLOG.md)
decision rather than an undocumented fail-soft change.

## Pushdown admission

Keep predicate-only equivalence separate from value-safe composition. A rewrite
safe for WHERE is not automatically safe beneath NOT/AND/OR, in a projection,
grouping key or aggregate. Bind user values as parameters; quote identifiers;
only closed allowlists may render SQL constants inline.

Key admission boundaries:

- Textualized complex/Decimal256 values cannot gain native value predicates,
  scalar transforms, grouping, DISTINCT or TopN merely by being exposed as VARCHAR.
- JSON equality uses restricted dotted-object paths and closed literal/type
  admission. Array-index paths and values colliding with non-scalar renderings
  stay local; the registry's conditional classification is not blanket permission.
- LOWER/UPPER remain local in every mode, including compositions. Character
  length renders as `char_length`, not Doris's byte `length`.
- The escaped-LIKE `starts_with` prefix rule has a restricted admission contract
  independent of arbitrary LIKE patterns. Explicit escapes require one non-NUL ASCII
  character; mode and NUL/CHAR hazards still apply.
- String modes primarily govern direct domains/comparisons/orderings. NULL_ONLY
  is not a global kill switch for every string value transformation. The standard
  `complex_expression_pushdown` and broader system `allow_pushdown_into_connectors`
  switches have different scopes; neither is replaced by another redundant switch.
- Aggregates retain overflow/rounding/NULL guards: SUM only for admitted DECIMAL
  types, AVG local, min_by/max_by with proven non-null payloads. Approximate
  aggregate admission is explicit and does not promise identical estimates.
- Only eligible INNER joins push. Outer joins stay local to avoid trusting base
  NOT NULL flags on a null-supplying output. EAGER bypasses cost, never type/safety gates.

Tests need both result and retained/pushed-plan assertions. To establish a local
oracle, disable all connector pushdown where the operation under test requires it;
disabling expression translation alone does not disable grouping or TopN.

## Metadata and statistics cache policy

`DorisJdbcMetadataFactory` preserves the upstream factory/listeners and places
`statistics_enabled` before both the per-transaction and shared JDBC caches:

```text
metadata statistics policy
  -> per-transaction CachingJdbcClient
  -> shared CachingJdbcClient
  -> retrying/read-only client
  -> Doris statistics reader
```

Disabled requests return unknown before cache reads or fills. Enabled requests
retain ordinary cache hits; disabled-first empty results cannot poison them.
The reader selects base-index statistics and rejects inconsistent zero snapshots,
but is not a general freshness detector for mixed-age nonzero estimates. It never
issues ANALYZE. Invalid/unavailable statistics fail soft, not the table scan.

## Connection and configuration contracts

`DorisReadDriver` permits normal connect-time URL host selection, resolves native
HA wrappers while idle, and detaches the selected physical connection **before
preparation/binding**. Safe-proxy/lock identity is checked. Abort targets the
physical attempt; the original owner is retained for full cleanup. Fresh attempts
can select another FE, but statements/results never migrate in flight.

`DorisTimeouts` supplies positive signed-int ranges and ceil normalization for
catalog/session, connection and scan paths. Unset query timeout leaves the native
session value unchanged. These are not universal setup wall-clock deadlines.

`DorisJdbcConfig` uses the non-connecting Connector/J parser/type classification
and the inherited BaseJdbcConfig URL constraint, including its flags. It rejects
X Protocol, decoded path/global/per-host database settings and nonempty URL
transforms/profiles. It never resolves DNS or instantiates those extensions at
validation. DNS-SRV uses ordinary JDBC URLs with `dnsSrv=true`; direct mysql+srv
schemes are outside the upstream configuration constraint.

## Cancellation lifecycle

Each prepared scan begins with a unique UUID statement-token comment; the Trino
query-id comment is tracing only. The helper matches the exact leading boundary,
not substrings in literals, quoted identifiers or audit searches. This is not
cryptographic protection against deliberately forged leading tokens.

`DorisPreparedStatement` suppresses Connector/J's numeric connection-ID cancel,
coordinates cancellation/cleanup races and aborts its pinned physical connection.
The cluster helper is asynchronous and independently best-effort:

1. Admit at most **72 logical requests per classloader**, including running,
   queued and delayed work. No caller-runs rejection policy or blocking admission.
2. At most **eight workers/connections** make probes. Each new helper receives
   the original 15s dispatch deadline and bounded connect/read properties.
3. Enable cluster processlist visibility (warn once per connector on failure),
   read completion state **before** scanning, and KILL only validated QueryIds of
   the matching statement.
4. Close JDBC resources with the post-connect watchdog still armed, then schedule
   another probe after at most 500ms. No idle helper or worker is parked in a sleep.
5. Preserve `observed` across reconnects. An empty scan before a token was ever
   seen is not release proof, even after socket abort ends client execution.
   Null/invalid IDs stay observable; accepted early no-op kills are retried.
6. Release admission exactly once on terminal failure, verified absence or expiry.
   Dispatch counters count logical accepted requests, not probes.

Reconnection adds setup work; this is not a connection-rate benchmark. DNS,
credential, custom authentication/socket or remote TLS setup can stall before
the watchdog is installed. Query cancellation processing also does not independently
prove every BE allocation was reclaimed. Preserve those limits in operational claims.

## Evidence discipline

The [metadata contract](METADATA.md) is an immutable-artifact/tuple/provenance
drift check and a map to proof. Handwritten rendering/guard consistency strings
are not structural validation of generated SQL and do not independently establish
equivalence. Rule output, actual admission/rejection, native observations, local
oracles and composition regressions carry that burden.

The [archive](archive/README.md) retains original probes and rejected alternatives.
Their version-bound observations are evidence to recheck, not runtime discovery
of arbitrary server compatibility.
