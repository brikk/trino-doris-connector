# NOTES — cluster-scoped query cancellation (multi-FE / LB correct)

User-reported production defect: Connector/J `Statement.cancel()` opens a SECOND connection
to the same JDBC URL and issues `KILL QUERY <connection-id>`. Behind a load balancer over
multiple FEs that lands on an arbitrary FE where connection ids are per-FE counters — a
silent no-op, or a kill of an unrelated same-user session. Our cancel tests were single-FE
only and masked it. Implementation adapted (with credit and thanks) from the Apache-2.0
reference in `dev.sort.doris.cancel` (DataGrip plugin) — production-hardened against a real
LB'd cluster; every step re-proven live on Doris 4.1.3 (single-FE + the 3-FE overlay).

## Marker decision: the existing statement comment, NOT session_context

Probe evidence (4.1.3):
- `SHOW FULL PROCESSLIST` `Info` is **untruncated** — a 120,059-char statement showed its
  TRAILING `/*trino_query_id=...*/` comment intact. Plain `SHOW PROCESSLIST` truncates at
  100 chars (never use it). `information_schema.processlist` is also full.
- The FULL processlist is **cluster-wide on 4.1.3** (visible from a foreign FE even without
  widening; `SET fetch_all_fe_for_system_table = true` is issued anyway as cheap insurance —
  the variable was renamed from `show_all_fe_connection` and default-flipped in 4.x).

So the marker every scan already carries ([DorisRemoteQueryModifier]) suffices — and it is
per-QUERY (the exact cancel scope), whereas the reference's `SET session_context =
'trace_id:...'` is per-CONNECTION and costs a connect-time round trip. No trace-id bind.

## The working sequence (live-proven, 4.1.3)

1. Async helper connection (same URL; ANY FE — probed helper-on-fe3).
2. `SET fetch_all_fe_for_system_table = true` (best-effort).
3. `SHOW FULL PROCESSLIST` -> rows where `Command != Sleep`, `Info` contains the marker,
   `Info` is not the helper's own SHOW/KILL -> `QueryId` (regex-validated:
   `[0-9a-f]{1,16}-[0-9a-f]{1,16}`).
4. `KILL QUERY "<QueryId>"` per match — Doris forwards FE-to-FE. Cross-FE proof: query
   owned by fe2, helper on fe3, kill returned in 22ms, victim released with
   "cancel query by user". Multi-scan Trino queries kill ALL their scans (the marker is
   query-scoped, so every match is legitimately ours — unlike the reference's ambiguity
   refusal, which guards per-connection guids).
5. "Unknown query id" == already finished == success (DEBUG). 4.1.3 nuance: repeating a
   kill for a COMPLETED query returns OK silently; a never-existed id errors.
6. **VERIFY AND RETRY** (`killUntilReleased`, 15s window / 500ms recheck): a kill issued in
   the FIRST INSTANTS of a query's life — the processlist row appears before the coordinator
   registers as cancellable — returns OK but is a SILENT NO-OP and the query runs to
   completion (live-reproduced: 1/25 tight-loop rounds; the victim COMPLETED normally
   despite the accepted kill — this was the "flaky pin" during development, root-caused).
   Re-scanning until no row matches closes the window; rows that outlive it (e.g. the
   killed-but-lingering send-blocked case) are left to the socket belt at DEBUG.

## kill-by-trace-id: version-unstable — still not used

The reference's production scar: `KILL QUERY "<trace-id>"` returns OK but is a SILENT
NO-OP on Doris 4.1.2. Our 4.1.3 probes found it WORKING (single- AND cross-FE) — i.e. the
behavior changed between patch releases. The QueryId path works on both, so it is the only
path used; `TestDorisClusterScopedCancel.testTraceIdKillBehaviorPin` pins the 4.1.3 state
and documents the instability so nobody "simplifies" to trace-id kills.

## Wiring

- Bookkeeping at `DorisClient.buildSql`: scan connection -> (marker, session), weak map —
  the busy streaming connection is NEVER touched at cancel time (reference finding: even
  `getClientInfo` can stall ~8s mid-query).
- `DorisClient.abortReadConnection`: PRIMARY = async dispatch of the cluster-scoped kill;
  BELT = the stock `connection.abort()` socket teardown (stays: it is the instant release
  on the owning FE and the only path when the helper cannot work). The blocked ResultSet
  read unblocks when the server errors the statement — the completion signal.
- Helper failures degrade silently (DEBUG): a cancel must never hang or fail because the
  helper couldn't do its work. In-flight dedupe per marker; daemon executor.
- Config: `doris.cluster-scoped-cancel` (default TRUE — strictly more correct).
- Observed side benefit: single-FE release evidence now arrives via processlist clearance
  in ~46ms (previously the socket-teardown fe.log race, ~125ms+).

## Proof surface

- `TestDorisClusterScopedCancel` (8): pure-function pins (QueryId validation, candidate
  classification, unknown-query-id), bookkeeping + helper-failure tolerance (throwing
  factory; in-flight slot clears), live single-FE kill pin, trace-id behavior pin.
- `TestDorisCancellation` (3): the structural release contract now ALSO asserts the
  cluster-scoped dispatch fired (same-JVM counter); both orderings green.
- `TestDorisMultiFeFailover` (+2, overlay-guarded): deterministic cross-FE kill
  (fe2-owned query, fe3 helper) and the connector end-to-end overlay cancel through the
  comma-list URL — THE tests this fix exists for.
