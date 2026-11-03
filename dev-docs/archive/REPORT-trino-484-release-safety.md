# Trino 484 release-safety implementation

**Approved steps 1–4 are implemented and qualified.** LOWER/UPPER and outer
joins stay local; read attempts are physically pinned. Final single-FE build:
368 passed with five overlay skips; the separate overlay replay passed all
five. Eleven resolution probes and the extracted-plugin smoke also passed.
No release was published. Other review findings outside this scope remain open.

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

## Step 4 — qualification completed in staged runs

The first full run with the three-FE overlay UP and `-PwithCancellation` ran
**373 tests: 372 passed, one failed, zero errors/skips**, in 16m01s. All
cancellation, multi-FE, and new safety regressions passed. The single failure
was an older derived-key test still expecting `lower(v)` grouping pushdown.
It now uses eligible `nullif(v,'absent')` for the positive control and explicitly
requires LOWER grouping/DISTINCT to remain local. A focused rerun of that test
and the complete Unicode fallback suite passed; production admission was not
relaxed. Original XML is retained in `full-qualification-xml/`.

The next clean all-at-once run on `3b9e2e4` was **incomplete**, not green: the
kernel's global OOM killer terminated the Gradle test executor (exit 137) after
11m47s. The partial XML records 261 tests without assertion failures but does
not qualify the remaining suite. `oom-evidence.log` retains the kernel event;
all validation containers reported OOMKilled=false. No unrelated process or
container was stopped.

Qualification was therefore split to reduce host memory pressure: the full
single-FE build with slow cancellation enabled, then a separate overlay-only
run. The overlay's five intentional skips in the single-FE run were accounted
for by the explicit replay, not presented as one no-skip full run. The 1GiB
Gradle heap is a warm-cache build-process limit; the test JVM remains at its
normal 1536MiB setting and no product admission is changed for memory pressure.

### Final test results

| Final run | Tests | Passed | Failed/errors | Skipped |
|---|---:|---:|---:|---:|
| Clean single-FE build, slow cancellation enabled | 373 | 368 | 0 | 5 overlay-only |
| Explicit three-FE replay | 5 | 5 | 0 | 0 |
| Opt-in R-04/R-05 resolution replay | 11 | 11 | 0 | 0 |

The five skipped cases in the full single-FE build are exactly the separately
replayed overlay class. This is staged coverage of the 373 planned cases, not
a claim that one all-at-once full run finished green without skips. Slow
cancellation was enabled, not silently excluded. The overlay replay includes
cross-FE KILL, connector cancellation/release, fresh-connection failover after
a follower stops, normal multi-host queries, and its framework naming check.

```sh
# Overlay stopped: reduce the resident FE/BE footprint during the full run.
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew clean build \
  -PwithCancellation --no-daemon --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8'

# After ./compose/multi-fe/up.sh restores a healthy golden overlay:
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew test \
  --tests '*TestDorisMultiFeFailover' --no-daemon --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8'
```

The full single-FE build finished successfully in 14m54s. Compilation, detekt,
17 evidence pins/metadata SHA checks, native version gates, new Unicode/outer
join/HA regressions, and 79-JAR assembly verification all passed. Deprecation
warnings remain. The main/overlay image digests and native versions stay at the
golden pins; the overlay restored its follower after the failover case.

Logs/XML under `/tmp/opencode/trino-484-safety/`:
`single-fe-qualification.log`, `single-fe-qualification-xml/`,
`multi-fe-replay.log`, `multi-fe-replay-xml/`, `resolution-probes.log`,
`resolution-probes-xml/`, and native overlay snapshots. Both failed/incomplete
combined attempts remain separately retained.

### Packaging and extracted-plugin installation

Locally packaged **development qualification artifacts**, not a published
version: `trino-doris-v484-0.2.0-SNAPSHOT.{zip,tar.gz}`. Both archives extracted
to one correctly named directory containing all 79 JARs. Every extracted JAR's
SHA-256 matches the clean assembly; checksums verified with `sha256sum --check`.
ServiceLoader registration, native read-driver class, exact metadata binary,
single Guice/stdlib, and absence of engine-provided SPI/JSON JARs were checked.

- ZIP SHA-256: `f2c36f1252d9bca711cda54f7f98373abbb431f5c2c7e756ec41521da7155414`.
- TAR SHA-256: `b256b55b03e0134f572c81643ebfbda44ea522d541ffd8cde1b7e7b8d81d436d`.

The ZIP-extracted directory (not Gradle's live output or an older snapshot) was
mounted read-only into a freshly recreated `trinodb/trino:484` container. A
multi-host catalog loaded the plugin via ServiceLoader; **24 SQL operations**
passed, including one expected strict invalid-JSON error. Checks covered:
Unicode outputs/predicates/grouping/nesting, all four R-04 aggregate/outer-side
NULL results under explicit EAGER, preserved remote INNER aggregation, native
JSON null versus SQL NULL, safe text `inf` versus invalid non-finite JSON,
selective results and fully pushed predicate/TopN plans, and catalog create/drop.
TAR extraction was byte-identical but was not separately installed in a server.

Artifacts and byte-level verification: `artifacts-qualified/`; smoke records:
`extracted-plugin-smoke.{log,json}`, `extracted-plugin-installation.log`,
`extracted-plugin-server.log`. Nothing was uploaded to a release or tagged.

### Scope and remaining decisions

R-04 is resolved by declining outer joins, not by claiming general upstream
nullable provenance is fixed. R-05 is resolved for the pinned read-attempt
design and measured driver/HA schedules; plain-TCP observations do not certify
every custom socket/TLS extension. The helper's supported URL kinds remain
unchanged; physical fallback still handles disabled/unavailable helpers.

This completes the approved safety/qualification scope. R-06 and other existing
review findings (including incremental packaging/ABI-label guard issues) were
not silently folded into this work or declared closed. Production release still
requires the user's release decision/version and any desired remaining review
triage. No release branch, tag, or GitHub publication was created.

Major steps are committed and pushed to `trino-484`, never a release branch.
The validation FE/BE, overlay and manual Trino containers were stopped after
qualification; volumes and fixtures were retained. Unrelated builds/containers
were not stopped. The working branch is ready for review of this scoped work,
not an instruction to publish a release.
