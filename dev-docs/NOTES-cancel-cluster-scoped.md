# NOTES — cluster-scoped query cancellation (multi-FE / LB correct)

User-reported production defect: Connector/J `Statement.cancel()` opens a SECOND connection
to the same JDBC URL and issues `KILL QUERY <connection-id>`. Behind a load balancer over
multiple FEs that lands on an arbitrary FE where connection ids are per-FE counters — a
silent no-op, or a kill of an unrelated same-user session. Our cancel tests were single-FE
only and masked it. Implementation adapted (with credit and thanks) from the Apache-2.0
reference in `dev.sort.doris.cancel` (DataGrip plugin) — production-hardened against a real
LB'd cluster; every step re-proven live on Doris 4.1.3 (single-FE + the 3-FE overlay).

## Marker decision: one token per statement, NOT per Trino query

Probe evidence (4.1.3):
- `SHOW FULL PROCESSLIST` `Info` is **untruncated** — a 120,059-char statement showed its
  TRAILING `/*trino_query_id=...*/` comment intact. Plain `SHOW PROCESSLIST` truncates at
  100 chars (never use it). `information_schema.processlist` is also full.
- The FULL processlist is **cluster-wide on 4.1.3** (visible from a foreign FE even without
  widening; `SET fetch_all_fe_for_system_table = true` is issued anyway as cheap insurance —
  the variable was renamed from `show_all_fe_connection` and default-flipped in 4.x).

The original implementation used the Trino query-id comment as its cancellation key.
The 2026-09-05 E-03/E-04/E-05 correction replaces that key with a fresh
`/*trino_statement_id=<uuid>*/` for each `DorisClient.buildSql` call, including retries.
Trino also closes individual scans early (for example, a local LIMIT); closing one does
not authorize killing its siblings. The query-id comment remains for tracing only.
Neither marker needs a session_context bind or an extra connection-time round trip.

## The working sequence (live-proven, 4.1.3)

1. Async helper connection (same URL; ANY FE — probed helper-on-fe3).
2. `SET fetch_all_fe_for_system_table = true` (best-effort).
3. `SHOW FULL PROCESSLIST` -> rows where `Command != Sleep`, `Info` contains the complete
   statement-marker comment and does not start with the helper's SHOW/KILL -> `QueryId` (regex-validated:
   `[0-9a-f]{1,16}-[0-9a-f]{1,16}`).
4. `KILL QUERY "<QueryId>"` per match — Doris forwards FE-to-FE. Cross-FE proof: query
   owned by fe2, helper on fe3, kill returned in 22ms, victim released with
   "cancel query by user". Only the matching statement is targeted; sibling scans retain
   their own tokens. That cross-FE forwarding measurement predates the lifecycle correction;
   the new lifecycle and isolation tests were run against the single-FE fixture.
5. "Unknown query id" == already finished == success (DEBUG). A repeated kill can either
   succeed or report Unknown query id, depending on server cleanup timing.
6. **VERIFY AND RETRY** (`killUntilReleased`, 15s window / 500ms recheck): a kill issued in
   the FIRST INSTANTS of a query's life — the processlist row appears before the coordinator
   registers as cancellable — returns OK but is a SILENT NO-OP and the query runs to
   completion (live-reproduced: 1/25 tight-loop rounds; the victim COMPLETED normally
   despite the accepted kill — this was the "flaky pin" during development, root-caused).
   Matching rows with an unassigned/invalid QueryId are retained for retry, never sent to KILL.
   An empty scan ends the loop only after this token has been observed and client execution
   has exited. A socket abort can finish client execution before a buffered request becomes
   visible on the FE, so an initially absent token is polled through the window.
   The loop is best-effort: unconfirmed release at the deadline is logged at DEBUG;
   socket abort and configured server-side query timeouts remain the fallbacks. Individual
   helper network operations are not bounded by that loop deadline (remaining E-14 work).

### Unassigned QueryIds

The old live test asserted every active processlist row had a valid QueryId and intermittently
failed. Doris 4.1.3 source confirms an unassigned ID renders as an empty string:
[`ConnectContext.ThreadInfo.toRow`](https://github.com/apache/doris/blob/4.1.3/fe/fe-core/src/main/java/org/apache/doris/qe/ConnectContext.java)
calls [`DebugUtil.printId`](https://github.com/apache/doris/blob/4.1.3/fe/fe-core/src/main/java/org/apache/doris/common/util/DebugUtil.java),
which returns `""` for null. This establishes a possible transient state, not the precise
interleaving of the historical failure. The deterministic retry test covers `""`, SQL NULL,
other invalid IDs, delayed visibility, and repeated valid-ID kills. Live diagnostics now
include the raw invalid value instead of failing on its first appearance.

## kill-by-trace-id: version-unstable — still not used

The reference's production scar: `KILL QUERY "<trace-id>"` returns OK but is a SILENT
NO-OP on Doris 4.1.2. Our 4.1.3 probes found it WORKING (single- AND cross-FE) — i.e. the
behavior changed between patch releases. The QueryId path works on both, so it is the only
path used; `TestDorisClusterScopedCancel.testTraceIdKillBehaviorPin` pins the 4.1.3 state
and documents the instability so nobody "simplifies" to trace-id kills.

## Wiring

- `DorisClient.buildSql` appends the statement token after query transformations, preserves
  parameter binding and query-id decoration, and wraps the prepared statement.
- `DorisPreparedStatement.cancel()` never delegates to Connector/J. It dispatches the helper
  once and aborts the captured connection without acquiring the driver's busy connection lock.
  Disabling `doris.cluster-scoped-cancel` disables the helper, not the socket fallback.
- Cancellation/close before execution prevents the queued supplier from reaching the driver.
  No wrapper monitor is held during JDBC work. Concurrent statement close and late-result
  cleanup wait for socket-abort completion before entering potentially draining JDBC close.
- A wrapper around ResultSet.next records exhaustion; closing a fully consumed scan neither
  dispatches a helper nor aborts its connection. No connection/session registry is retained.
- Trino 483 calls Statement.cancel before abortReadConnection, and only invokes the latter
  once the page source has adopted its result set. This includes a gap AFTER executeQuery
  returns but BEFORE adoption. Both phases are now covered by the statement wrapper.
- `DorisClient.abortReadConnection` retains socket-only behavior for other BaseJdbc read paths.
  Helper failures log at DEBUG; daemon executor capacity/network bounds remain E-14 work.
- This does not change Trino's scheduling of close: a driver thread blocked in synchronous
  ResultSet.next can still delay operator destruction. No universal cancellation latency is promised.

## Proof surface

- `TestDorisScanCancellation`: real Trino JdbcPageSource over fake JDBC for queued execution,
  blocked execution, unadopted results, normal completion, and sibling-stream isolation;
  also a live single-FE page-source close before the first result. Included in default CI.
- `TestDorisPreparedStatement`: concurrent close/late-result ordering against a paused cancel,
  wrapper-preserving unwrap, idempotent close, and socket fallback without a helper.
- `TestDorisClusterScopedCancel`: deterministic scripts drive the production helper loop;
  a live test cancels one of two same-query-id statements and verifies its sibling survives.
- `TestDorisCancellation`: opt-in slow end-to-end query kill and server-side timeout tests;
  helper dispatch remains informational there, with mandatory dispatch proved in the new suite.
- `TestDorisMultiFeFailover` (overlay-guarded): deterministic cross-FE kill
  (fe2-owned query, fe3 helper) and the connector end-to-end overlay cancel through the
   comma-list URL.

Verification on 2026-09-05: full `./gradlew build -PwithCancellation` passed in 13m08s:
273 passed, 5 multi-FE overlay tests skipped, zero failures; detekt and plugin assembly passed.
Use `mise exec -- ./gradlew build -PwithCancellation` to select the repository's JDK.
