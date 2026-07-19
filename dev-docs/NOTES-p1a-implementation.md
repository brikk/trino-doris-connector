# NOTES — P1a implementation (module scaffold + read path foundation)

Status: P1a complete. `./gradlew :trino-doris:build` green (compile + detekt + 35 tests +
pluginAssemble + verifyPluginAssembly). Coded against
`LEDGER-p0-type-and-capability.md` (authoritative) and PLAN §3/§4/§5/§10-P1.

Module: `jvm/trino-doris`, package `dev.brikk.doris.trino.plugin`, Kotlin only, Trino 483 BOM,
Connector/J 9.7.0. Classes: `DorisPlugin`, `DorisClientModule`, `DorisJdbcConfig`, `DorisConfig`,
`DorisClient`, `DorisTypeMapping`, `DorisColumnType`. Tests: `DorisQueryRunner`,
`TestDorisP1aSmoke` (23 live tests vs compose 4.1.3 @9130), `TestDorisTypeMapping`,
`TestDorisJdbcConfig` (12 unit tests).

## Deviations from the ledger / plan / task brief (each with reason)

1. **`mysql-connector-j` is `implementation`, not `runtimeOnly`** (assembly-proof §6 recipe
   said `runtimeOnly`). `DorisClientModule`/`DorisJdbcConfig` reference
   `com.mysql.cj.jdbc.Driver` and `ConnectionUrlParser` at compile time — the same shape as
   upstream trino-mysql (driver at compile scope). No assembly impact: runtimeClasspath is
   identical; `verifyPluginAssembly` still proves the §6 exclusion contract.

2. **`DecimalModule.withNumberMapping(UNSUPPORTED)`, not `ON_BY_DEFAULT`** (task brief said
   "DecimalModule.withNumberMapping" following the SingleStore/MySQL shape, which uses
   ON_BY_DEFAULT). ON_BY_DEFAULT exposes the `MAP_TO_NUMBER` decimal surrogate that the ledger
   explicitly REJECTS for Doris (silent scale loss on DECIMAL>38 — ledger §A Decimal256 stance;
   STOCK). Our DECIMAL(p>38) mapping is unconditionally VARCHAR-of-exact-text, so the
   decimal-mapping session knobs would be dead-and-misleading surface. UNSUPPORTED keeps the
   module installed (config classes bound) without that escape hatch.

3. **`JdbcMetadataConfig.bulkListColumns` left `false`** (SingleStore/MySQL set it true).
   The bulk path routes through `DatabaseMetaData.getColumns`, proven lossy on Doris (PROBE §1
   Impl #1). Per-table listing goes through `DorisClient.getColumns`'s
   `information_schema.columns.COLUMN_TYPE` query instead. If a user flips the
   `bulk_list_columns` session property, base-jdbc's `getAllTableColumns` fails loud with
   NOT_SUPPORTED (never silently wrong). P1b/P2 option: implement bulk listing as a single
   information_schema query.

4. **`unsupported-type-handling=CONVERT_TO_VARCHAR` is not honored** (the property exists —
   base `JdbcModule` binds it — but `DorisTypeMapping` returns empty for ARRAY/MAP/STRUCT/
   BITMAP/HLL/QUANTILE_STATE/AGG_STATE regardless, so those columns are always hidden in P1a).
   Reason: textualizing BITMAP/HLL/AGG_STATE is meaningless (NULL text / raw state bytes,
   PROBE §BITMAP), and exposing ARRAY/MAP/STRUCT wire text needs a deliberate decision the
   ledger defers. P1b item: either honor the knob for the text-safe complex types or reject
   the setting explicitly so it cannot be a silent no-op.

5. **DECIMAL(p>38) → unbounded VARCHAR** — the ledger offered "VARCHAR (or fail-loud)"; VARCHAR
   chosen because the read path is wire-exact (PROBE Impl #3) and hiding an exactly-readable
   column would be worse. Pushdown disabled on it.

6. **BOOLEAN discrimination is `tinyint(1)` vs `tinyint(4|none)`**, refining the ledger's
   "`boolean` vs `tinyint`" wording: live 4.1.3 `information_schema.columns.COLUMN_TYPE` emits
   `tinyint(1)` for BOOLEAN and `tinyint(4)` for TINYINT (verified against p0_probe; Doris
   TINYINT DDL has no display width, so `tinyint(1)` is unambiguous). A literal `boolean`
   string is also accepted.

7. **Scalar DOUBLE ±MAX boundary**: p0_probe rows storing `±1.797693134862316e+308` (Doris's
   16-significant-digit rendering of DOUBLE max) reparse to ±Infinity in Java — the scalar twin
   of ARRAY F3. Surfaced faithfully per the ledger's F3 ruling (never a silently-wrong finite
   value, never a scan-killing throw); tested in `testDoubleInfinityRowsReadInsteadOfPoisoningTheScan`.

8. **Timeouts**: `doris.connect-timeout` defaults to 10s (ledger §B requires an explicit finite
   connectTimeout; exact value was left as "a P1 config decision" — decided here). Socket
   timeout is NOT set: a finite socket timeout would kill legitimate long scans; runaway
   queries are bounded server-side via `doris.query-timeout` (session `query_timeout`) and
   `Statement.cancel()` (ledger §D). Revisit in P1b alongside `DorisSessionProperties`.

9. **LIMIT pushdown enabled** (task brief allowed it "if free and evidenced"): one-line
   `limitFunction` + `isLimitGuaranteed=true`; evidence = stock Trino 483 pushed LIMIT to
   Doris 4.1.3 as a single remote statement (STOCK pushdown ledger; ledger §E "positive
   baselines to preserve"). Tested (row count + `isFullyPushedDown`).

10. **`connectionTimeZone`/`forceConnectionTimeZoneToSession` NOT set** (MySqlClientModule sets
    them). All DATETIME reads go through zoneless `getObject(LocalDateTime)`, so the session
    zone cannot shift values (PROBE §6); `doris.time-zone` only sets the server-side session
    variable for server-evaluated expressions.

11. **Read-only enforcement is PARTIAL in P1a (by scope)**: `toWriteMapping` throws
    NOT_SUPPORTED (blocks INSERT/CTAS at planning), but the full G7 defense-in-depth —
    `ReadOnlyDorisClient` forwarding wrapper, `DorisReadOnlyAccessControl`, the `system.execute`
    denial proof (base `JdbcModule` DOES auto-bind `ExecuteProcedure` + flush-cache procedures),
    and the SELECT-only fixture account — is P1b. Until P1b lands, the stock DELETE danger
    (STOCK: a real row was destroyed) is NOT fully closed. The `query()` PTF is already NOT
    bound (G7.4).

12. **Remote-SQL observability assertion deferred**: pushdown is proven at plan level
    (`isFullyPushedDown` / `isNotFullyPushedDown(FilterNode)`) instead of via the FE audit log.
    P1b hook noted in `testNumericDatePredicatesAreFullyPushedDown` (audit-log `Stmt=` is the
    proven substrate, ledger §E).

13. **`kotlin-stdlib` lands in the plugin dir** — expected for a Kotlin connector (plugin-local,
    parent-last); the assembly-proof jar list was produced by a Java-less spike so it does not
    list it. `verifyPluginAssembly` proves the actual contract (no parent-first SPI jars,
    single `guice-*-classes.jar`, base-jdbc + Connector/J present).

14. **Table listing restricted to `TABLE` + `VIEW` types** (`getTableTypes` override). Doris
    reports LOCAL TEMPORARY / SYSTEM TABLE / SYSTEM VIEW types too (PROBE §10); those live in
    hidden schemas anyway, this just makes the filter explicit. Doris MVs need a dedicated
    labeling probe before being exposed (PLAN §4.4 open item).

15. **Trino JSON canonicalization**: Trino's JSON type sorts object keys, so
    `json_format(c_json)` returns `{"arr":...,"k":...,"n":...}` for Doris wire text
    `{"k":...,"n":...,"arr":...}`. Engine-level JSON semantics, not a connector wire defect;
    encoded in the smoke test expectations.

## Fixtures

- `p0_probe` / `p0_array_spike` never mutated (read-only SELECTs only).
- `p1_smoke` database is dropped/recreated by `TestDorisP1aSmoke` setup over direct JDBC
  (`replication_num=1`, single-BE compose cluster): in-range LARGEINT extremes
  (±(10^38−1)), integer min/max, unicode/emoji, empty-string-vs-NULL, zero-date DATETIME/DATE,
  all-NULL row. LARGEINT out-of-range fail-loud is proven against p0_probe's ±(2^127−1) rows.

## Open items for P1b

- Full read-only defense-in-depth (G7): `ReadOnlyDorisClient extends ForwardingJdbcClient`,
  `DorisReadOnlyAccessControl`, **prove `system.execute` denied** (replace/fork the module
  binding if access control can't make it airtight), SELECT-only fixture account + denial
  suite proving absence of side effects (DELETE/UPDATE/DDL currently reach base-jdbc paths).
- `DorisSessionProperties` (re-home `doris.query-timeout` per-query; SR K11/R2) and
  per-statement `setQueryTimeout` on the execute path; live cancellation test
  (`SHOW PROCESSLIST` clearance).
- FE audit-log assertion hook for verbatim pushed SQL.
- Decide/implement stance for `unsupported-type-handling` (item 4) and bulk column listing
  via information_schema (item 3).
- IPADDRESS equality domain pushdown (ledger §A allows it; disabled in P1a).
- Multi-FE failover remains an open P0-exit obligation (ledger §F NOT-DONE) — needs a ≥3-FE
  cluster.
- ARRAY allowlist decoding + `contains`/`arrays_overlap` rules are P2.
