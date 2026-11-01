# Trino 484 release-safety implementation

Golden stack stays fixed: Trino 484, Doris 4.1.5-release-917b20be838 (digest-pinned
FE/BE), Connector/J 26.7.0, released metadata 0.21.0, Kotlin 2.4.10, JDK 25.
Implementation checkpoints: 2026-10-31 through 2026-11-01.
Baseline checkpoint: `acbbfab`; the historical failing measurements remain in
`REPORT-trino-484-doris-4.1.5-lock.md`. No release is authorized by these steps.

## Step 1 — local Unicode case folding

Removed LOWER/UPPER admission and their obsolete active evidence entries (17
active pins remain). Functions containing either operation also stay in Trino;
ASCII-looking literals or VARCHAR metadata do not prove column contents ASCII.
Constant expressions already evaluated by Trino need no remote equivalent.

Regression coverage includes all four string modes, sharp-s, contextual sigma,
dotted I, NULL/ASCII controls, projections, predicates, grouping, nested
coalesce/length, and audit proof that neither function appears in remote SQL.
The native Trino expectations are unchanged; separate Doris observations now
explicitly record the measured divergence rather than claiming identity.
Other eligible derived grouping expressions still push.

Focused validation: **21 tests passed, zero failures/errors/skips**, plus
detekt. Initial test-only failures were corrected for bounded VARCHAR result
types and an aggregation-with-project plan shape; no result guard was relaxed.

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew test \
  --tests '*TestDorisCaseFoldLocal' --tests '*TestDorisB1ScalarPushdown' \
  --tests '*TestDorisPushdownEvidenceDrift' detekt --no-daemon --console=plain
```

Log: `/tmp/opencode/trino-484-step1-rerun.log`; XML:
`/tmp/opencode/trino-484-safety/step1-xml/`.

## Step 2 — conservative outer-join restriction

The legacy join admission gate now accepts only INNER joins, even under EAGER.
The complex join path remains disabled. LEFT/RIGHT/FULL joins stay in Trino,
so an outer join cannot propagate a falsely non-null synthetic payload into a
pushed min_by/max_by. Base-table and matched INNER JOIN aggregates remain fully
pushed. This is a conservative restriction, not a claim to have implemented
general effective-nullability propagation.

Focused validation: **24 tests passed, zero failures/errors/skips**, plus detekt:
R-04's four counterexamples now return NULL, all supported-key INNER join/cost/
composition controls pass, outer/null-safe/non-equi/empty-side cases stay local,
and date projection identity still works through a pushed INNER join.

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew test \
  --tests '*TestDorisOuterJoinSafety' --tests '*TestDorisP5JoinPushdown' \
  --tests '*TestDorisP6DateProjection' detekt --no-daemon --console=plain
```

Log: `/tmp/opencode/trino-484-step2.log`; XML:
`/tmp/opencode/trino-484-safety/step2-xml/`.

## Step 3 — physically pinned read attempts

`DorisReadDriver` retains Connector/J's ordinary URL host selection at connect
time, resolves nested HA wrappers while idle, and detaches the selected native
connection from mutable HA routing **before any statement is prepared/bound**.
It verifies that the native safe proxy and execution lock now belong to the
physical connection, failing closed if that contract cannot be established.
The pinned view delegates reads/abort to that physical connection and retains
the original HA owner for complete close/pool cleanup. It runs below
DriverConnectionFactory's OpenTelemetry wrappers, retaining instrumentation.

This intentionally makes each read connection one physical attempt. Replication/
load-balancing selection applies when opening a connection, not to later
per-statement routing/read-only toggles. Failed attempts end; fresh connections
retain normal host selection/failover, and Trino owns query retries. No busy
proxy lookup, JDBC unwrap, or routing call is performed during cancellation.
The existing bounded cluster QueryId helper and no-numeric-ID-kill wrapper stay
in place. The helper-only DorisCancelDriver remains separate.

Important rejected approach: merely unwrapping a prepared statement and saving
its session is insufficient. The 26.7.0 statement can still execute through a
mutable connection wrapper after a routing change. A real identity probe caught
that mismatch; the production fix instead pins the connection before preparation.

Focused validation: **30 tests passed**, including driver cancellation helpers,
controlled page-source races and concurrent cleanup. Real factory integration:
**36 tests passed**, plus detekt and assembly verification, including static/
dynamic catalogs, scalar reads, prepared parameter fidelity in client/server
modes and the actual new production read driver.

The HA regressions cover single/comma-list/load-balancing/replication URLs with
helper disabled/failing and adopted streaming; execute-phase cancel is now
**0–1ms** in the integration run rather than ~4s. Concurrent cancel/close took
**1ms / 1ms**. These are observations, not general latency SLAs. A fresh
connection skips an unavailable first host. A replica-prepared scan keeps its
captured physical connection even after the retained logical owner's route is
changed; abort releases that scan while the other current connection survives.
Closing the pinned view also closes the original HA owner.

Expected post-abort streaming ResultSet.close SQLExceptions remain recorded
separately; no claim of exception-free driver teardown is made. Measurements
use plain TCP/same-FE aliases to isolate locks; custom sockets/TLS and cross-FE
forwarding are not inferred from them. Step 4 provides the separate overlay proof.

Logs: `/tmp/opencode/trino-484-step3-final.log` and
`/tmp/opencode/trino-484-step3-integration.log`; XML under
`/tmp/opencode/trino-484-safety/step3-xml/` and `step3-integration-xml/`.
The opt-in R-05 probe now reuses this durable production-driver regression suite.

## Step 4 — qualification pending

The first full run with the three-FE overlay UP and `-PwithCancellation` ran
**373 tests: 372 passed, one failed, zero errors/skips**, in 16m01s. All
cancellation, multi-FE, and new safety regressions passed. The single failure
was an older derived-key test still expecting `lower(v)` grouping pushdown.
It now uses eligible `nullif(v,'absent')` for the positive control and explicitly
requires LOWER grouping/DISTINCT to remain local. A focused rerun of that test
and the complete Unicode fallback suite passed; production admission was not
relaxed. Original XML is retained in `full-qualification-xml/`.

The full qualification will be rerun before a green full-suite claim. Local
ZIP/TAR checksum and extraction round-trips passed; final extracted-plugin
server smoke remains pending. No publication is performed by these checks.

Major steps are committed and pushed to `trino-484`, never a release branch.
