# NOTES — P1b implementation (read-only defense in depth + remaining P1 obligations)

Status: P1b complete. `./gradlew :trino-doris:build` green. Builds on P1a (a83cbb4);
coded against PLAN G7/§7/§10-P1 and ledger §C/§D.

New classes: `ReadOnlyDorisClient`, `DorisReadOnlyAccessControl`, `DorisRemoteQueryModifier`,
`DorisQueryBuilder`, `DorisSessionProperties`. New tests: `TestReadOnlyDorisClient`
(reflection audit), `TestDorisReadOnly` (denial suite), `TestDorisTrinoRoAccount`,
`TestDorisCancellation`, `TestDorisRemoteSql` (audit-log proofs), plus `DorisTestCluster`
test utility. P1a's `unsupported-type-handling` flag resolved (honored where the ledger
permits).

## How `system.execute` denial is made airtight (G7.5)

Verified in the 483 sources first:
- `JdbcConnectorFactory.create` hard-installs `new JdbcModule()`, whose
  `newSetBinder(Procedure).addBinding().toProvider(ExecuteProcedure)` CANNOT be removed by a
  connector module (Guice multibinders are additive; overriding individual set elements is
  unsupported). "Replacing the binding" would mean forking `JdbcConnectorFactory` + keeping a
  copy of `JdbcModule` in sync across upgrades — a real fork cost.
- `ExecuteProcedure.doExecute` does NOT call `JdbcClient.execute()` — it opens
  `jdbcClient.getConnection(session)` (the 1-arg overload) and runs
  `Statement.executeUpdate(query)` directly.
- The 1-arg `getConnection(session)` overload has exactly TWO callers in all of base-jdbc:
  `ExecuteProcedure` and `JdbcMergeSink` — both write paths. Nothing on the read path uses it.

The denial is therefore made airtight WITHOUT the fork, at three independent layers:

1. **Engine boundary (access control):** `DorisReadOnlyAccessControl.checkCanExecuteProcedure`
   denies everything except `system.flush_metadata_cache` (PLAN §7 keeps the harmless cache
   flush). `CALL doris.system.execute(...)` fails during analysis, before the procedure's
   method handle is ever invoked; connector-level access control cannot be disabled by a user.
2. **Client choke point:** `ReadOnlyDorisClient` overrides `getConnection(session)`,
   `getConnection(session, outputHandle)`, and `execute(...)` to throw. Even if the
   access-control layer were somehow bypassed and the procedure invoked, it cannot obtain a
   connection — the escape hatch is physically shut in-process. (Proven by the reflection test
   invoking the overloads directly.)
3. **Server (privilege):** the `trino_ro` SELECT-only account cannot mutate even over a raw
   connection (proven live: `LOAD command denied` / `Access denied`).

Test proof: `TestDorisReadOnly.testSystemExecuteIsDeniedAndHasNoSideEffects` attempts
INSERT/DELETE/CREATE-DATABASE payloads through `CALL doris.system.execute`, asserts the
Access-Denied failure AND (via direct JDBC) that no row/database appeared.
`flush_metadata_cache` is asserted allowed. This closes the PLAN §10-P1 exit criterion
"prove system.execute is denied"; the "replace/fork the module binding" contingency was NOT
needed because access control + the client choke point are airtight.

## ReadOnlyDorisClient (G7.2)

- `ForwardingJdbcClient` subclass; 31 mutating methods throw
  `The Doris connector is read-only...(rejected: <op>)`; `supportsMerge()` answers `false`.
- Mutator parameters are declared nullable so the read-only throw wins over Kotlin null-check
  intrinsics (the guard rejects even null/garbage arguments).
- `TestReadOnlyDorisClient` classifies the ENTIRE 483 `JdbcClient` surface (76 signatures) into
  MUTATORS / CAPABILITY_DENIALS / READ_AND_PROBE_METHODS by reflection; an unclassified method
  (future base-jdbc upgrade) fails the test. Every mutator is asserted to (a) be declared by
  the guard class itself and (b) throw without touching a booby-trapped delegate.

## Denial suite results (`TestDorisReadOnly`, 15 tests)

CREATE/DROP(CASCADE)/RENAME SCHEMA, CREATE TABLE, CTAS, DROP TABLE, ALTER (add/drop/rename
column, rename table), COMMENT ON TABLE/COLUMN, INSERT, DELETE (the stock-DELETE danger
re-run — row survives), UPDATE, MERGE, TRUNCATE, CALL system.execute — all fail with
`Access Denied: ...` and direct-JDBC side-effect checks confirm Doris state is untouched.
`CALL system.flush_metadata_cache()` succeeds. Reads still work under the bound access
control. Note: plain `DROP SCHEMA` (no CASCADE) hits the engine's non-empty check before
access control — the CASCADE form proves the connector-boundary denial.

## SELECT-only account (G7.1)

`trino_ro` (SELECT_PRIV on `internal.*.*`; GRANTs documented in compose/README.md).
`TestDorisTrinoRoAccount`: full read path works through Trino as `trino_ro` (schemas, scans,
pushdown), and raw JDBC mutations as `trino_ro` are denied BY DORIS (INSERT/DELETE/UPDATE
`LOAD command denied`, DDL `Access denied`), with side-effect checks. UPDATE needs a
UNIQUE-key fixture table to get past Doris's table-model check and reach the privilege check
(DUP-table UPDATE fails on the model check instead — same as STOCK observed).

## Cancellation / timeout (ledger §D) — measured numbers

- `CALL system.runtime.kill_query(...)` on a live 500MB-wire scan: Trino query FAILED with the
  kill message; the Doris query left `information_schema.processlist` **36-296 ms** after the
  kill across runs (ledger baseline ~1 s; CI bound 15 s). The remote statement is identified
  in the processlist by the `trino_query_id=<id>` comment — no heuristics.
- `SET SESSION doris.query_timeout = '2.00s'`: Doris-side work released (processlist cleared
  by the timeout checker) **~9-26 s** after the scan appeared across runs — the checker sweeps
  periodically, so the release lags the 2 s limit by the sweep period (CI bound 60 s). The
  Trino query then fails with the timeout error when the stalled scan next reads the killed
  connection. The cancellation tests are ordered and kill their query via `kill_query` in
  `finally`, so a failing run cannot leave a CPU-burning zombie query starving later suites
  (this exact interference was observed and fixed during P1b).

### query_timeout mechanism deviation (evidence-driven)

The P1 brief and SR K11 suggested per-statement `Statement.setQueryTimeout`. Live P1b probes
(throwaway, /tmp/opencode/p1b-probe) showed:
- Connector/J's client-side timeout timer does NOT cover the streaming-results drain phase —
  a slow/stalled drain never triggers it (the first implementation hung for minutes).
- Server-side `SET query_timeout = N` DOES kill a still-running (send-blocked) query:
  `"query is timeout, killed by timeout checker"`. Small results that fit server buffers
  complete server-side immediately and are correctly not killed.

`DorisSessionProperties.query_timeout` is therefore applied SERVER-side per scan connection in
`DorisClient.buildSql` (session scope only, ledger §B; one extra `SET` round-trip only when
the property is set). The client-side timer was dropped as proven-ineffective for scans.

## Remote-SQL observability (PLAN §8)

- `DorisRemoteQueryModifier` appends `/*trino_query_id=<id>*/` to every remote statement
  (query id validated against `[a-zA-Z0-9_]+` — fail-loud, no comment breakout). It composes
  with a user-configured `query.comment-format` modifier rather than replacing it.
- Seam note: the scan path applies its modifier inside `DefaultQueryBuilder` (verified at
  483), and `RemoteQueryModifierModule` binds the `RemoteQueryModifier` key non-optionally —
  so `DorisQueryBuilder` (bound via the `QueryBuilder` optional binder) hands the wrapped
  modifier to `DefaultQueryBuilder`; `DorisClient` also wraps the modifier passed to
  `BaseJdbcClient` for the (denied) execute/insert paths.
- `DorisTestCluster.awaitAuditLogStatements` greps `fe.audit.log` in the FE container by the
  query-id marker (audit logger flushes asynchronously, seconds of lag). `TestDorisRemoteSql`
  proves: the pushed numeric domain appears verbatim in the remote SQL
  (`SELECT `n` FROM ... 1000995 ... /*trino_query_id=...*/`), and a string equality predicate
  does NOT appear in the remote SQL (G5 negative proof at the wire).

## unsupported-type-handling resolution (P1a flag)

Honored — not removed — because the ledger explicitly permits a VARCHAR policy for these
types: `unsupported-type-handling=CONVERT_TO_VARCHAR` now exposes ARRAY/MAP/STRUCT as
unbounded VARCHAR of the server-rendered wire text (read-only, DISABLE_PUSHDOWN; ledger §A
"unsupported column, or VARCHAR-of-whole-array-text" / "unsupported or VARCHAR-of-text").
Opaque engine states (BITMAP/HLL/QUANTILE_STATE/AGG_STATE/`unknown`) remain hidden under
EVERY policy — their "text" is NULL or raw state bytes (PROBE Impl #9). Config surface and
behavior now agree; tested at unit (`TestDorisTypeMapping`) and live (`TestDorisP1aSmoke`).

## Other notes

- `DorisSessionProperties` is bound via `bindSessionPropertiesProvider`; `query_timeout`
  default flows from `doris.query-timeout` catalog config (nullable = no client-side default).
- Access control binding uses `JdbcModule`'s `ConnectorAccessControl` optional binder;
  `DorisReadOnlyAccessControl extends` plugin-toolkit's `ReadOnlyAccessControl`, whose
  non-overridden methods inherit the SPI interface defaults — verified at 483 that those
  defaults DENY (schema DDL, truncate, grants/roles, branches, functions).
- Fixture DBs owned by P1 suites: `p1_smoke`, `p1_readonly`, `p1_ro_smoke`, `p1_cancel`
  (persistent 500MB-wire table for cancellation); `p0_*` never mutated.

## P1 exit criteria status (PLAN §10-P1)

| Criterion | Status |
|---|---|
| Scaffold module/plugin/assembly | DONE (P1a, verified assembly) |
| Metadata + scalar type mappings | DONE (P1a, ledger §A) |
| Streaming + cancellation | DONE (P1a streaming; P1b live cancellation numbers above) |
| Read-only client wrapper + access control | DONE (P1b, reflection-audited) |
| SELECT-only fixture user | DONE (P1b, documented + belt-tested) |
| system.execute denial proven | DONE (P1b, three layers + side-effect proof) |
| No pushdown beyond exact numeric/date domains | HOLDS (G5 negative proof at the wire) |
| Smoke + type/read-only suites green | DONE (81 tests across 8 suites) |

## Remaining P1 gaps / parked items

- **Multi-FE failover** — parked (needs a ≥3-FE cluster; ledger §F NOT-DONE).
- Bulk column listing via a single information_schema query (perf, optional).
- IPADDRESS equality domain pushdown (ledger permits; P2 alongside expression rules).
- brikk metadata release remains parked for user approval (P2 dependency).
