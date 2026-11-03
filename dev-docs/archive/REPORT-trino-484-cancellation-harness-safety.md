# Cancellation targeting and disposable-test safety

Date: 2026-11-01. Worktree/branch: `trino-484`. Golden Trino 484 / Doris
4.1.5-release-917b20be838 / Connector/J 26.7.0 / metadata 0.21.0 / Kotlin
2.4.10 / JDK 25 are unchanged. No publication or release-version decision.

**R-10 and R-15 are implemented and qualified.** R-10 was committed/pushed as
`6cc9824` before R-15 implementation. Final staged qualification: 378 single-FE
passes with slow cancellation enabled, five separately authorized overlay
passes, and the explicit no-permission/identity safeguards below. All seven
validation containers and the task-owned idle Gradle daemon were stopped;
task temporary working files were cleared. Volumes and evidence were retained.

## R-10 — connector-owned leading token

`DorisClient.buildSql` now prefixes each scan with its unique statement comment,
before generated SQL and tracing comments. The cancellation candidate matcher
requires that exact leading comment, its whitespace boundary, and the complete
UUID-shaped token. It no longer searches arbitrary SQL text. Tracing by Trino
query ID remains unchanged and never becomes cancellation authority.

Tests reject the exact comment inside single/double-quoted literals, quoted
identifiers, escaped/doubled strings, line/block comments, trailing positions,
and sibling-token SQL. They retain genuine generated/traced queries, including
quoted parameter values. This boundary is not a cryptographic authentication
scheme: deliberately issuing SQL with a copied leading token is outside the
accidental audit-search classification fix.

The production-helper scripted regression includes an unrelated audit-search
row on every processlist scan and proves no KILL is sent for it. Existing delayed
visibility, unassigned QueryIds, retries, sibling isolation and repeat-kill
controls remain. Production scan tests assert the generated token begins at
offset zero; real-driver helper/abort, streaming, and slow cancellation pass.

Focused qualification: **37 passed, zero failures/errors/skips**, detekt and
79-JAR assembly verification passed; slow cancellation enabled. Log/XML under
`~/.cache/trino-doris-484-r10-r15/`: `r10-qualification.log`, `r10-xml/`.
No new cross-FE replay is claimed at this checkpoint. The old diagnostic now
asserts rejection of the literal case rather than pinning the resolved defect.

R-10 implementation checkpoint: **`6cc9824`**, committed and pushed before R-15
implementation started.

## R-15 — disposable-cluster authorization

Every shared fixture connection now requires **`DORIS_TEST_ALLOW_MUTATION=1`**
before even opening a socket or running a Docker identity check. Raw fixture
connections are centralized, including direct root/SELECT-only accounts, HA
driver probes, query-runner startup and the overlay. The ordinary production
connector remains read-only and does not consult these test-only permissions.

Compose FE/BE services declare a disposable-purpose label. The guard verifies:

1. Exact Compose project/service/purpose, pinned image family, network IP and
   host-port mappings for the entire known topology; all nodes must be running
   at initial authorization.
2. The Docker-owned FE's native **ClusterId, complete FE and BE host rosters,
   and golden versions**, using read-only metadata commands by immutable ID.
3. The same native identity through **each actual JDBC connection**, before the
   caller receives it. Current Docker labels and original IDs are also rechecked
   on every connection; a cached initial proof cannot authorize a replaced or
   relabeled container. Inspections are batched per topology.

The overlay additionally requires **`DORIS_TEST_ALLOW_FAILOVER=1`**. Without it,
the optional class skips without probing ports. An explicitly requested but
unsafe/unavailable overlay fails loud. Every stop/start revalidates ownership,
rejects unknown/replaced containers, and passes the original immutable ID to
Docker instead of a mutable name. A stopped known follower can be restarted;
fresh connections during the authorized failover still verify the full native
roster (including the temporarily unavailable follower).

These are guardrails against accidental target selection, not a security
sandbox against someone controlling Docker, SQL metadata and routing. Merely
having the same ports or Doris version is no longer sufficient authorization.
Fixture semantics are unchanged: failed/mismatched fingerprints can still DROP
and rebuild their owned schemas **on the explicitly authorized disposable
cluster**. The previous misleading preservation wording has been corrected.

CI/release explicitly set the mutation opt-in in the job environment and do not
implicitly authorize failover. Both flags are Gradle test inputs, so changing
them cannot reuse an up-to-date result from another permission setting. Local
instructions show the same explicit opt-ins; older containers without labels
are recreated by `compose/up.sh` without deleting their volumes.

### Focused evidence

- **Seven pure unit tests** pass without a cluster or opt-in, plus detekt. They
  prove refusal before any Docker/socket/SQL work without permission; bad labels,
  project/service/image/network/ports; a foreign native ClusterId/FE/BE/version;
  revoked permission and replacement/relabeling after a cached proof; and
  immutable-ID lifecycle behavior with separate failover permission.
- **Six tooling tests** pass, including centralized raw-connection and
  lifecycle-boundary regressions plus rendered disposable-purpose labels.
- **Four actual Gradle qualification cases** pass: the existing assembly/ABI
  guards and a new child-process run of a real fixture suite with both flags
  removed. The latter must fail specifically at the mutation opt-in boundary;
  a connection error or ordinary test failure is not accepted as proof.

Evidence: `r15-unit-final.log`, `r15-unit-final-xml/`,
`r15-tooling-qualification.log` under `~/.cache/trino-doris-484-r10-r15/`.
The intentionally refused child run is a passing negative guard test, not an
ignored failure in the authorized connector suite.

### Full-run resource interruption

The first clean single-FE build explicitly opted into fixture mutation and slow
cancellation. Its test executor exited **137 after 8m02s**, during a confirmed
**host-global OOM**. Kernel evidence is retained in `r15-oom-evidence.log`.
The partial XML records 218 discovered tests, zero assertion failures/errors
and six skips, but is **not completed qualification**. It is retained separately
in `r15-full-single-fe-initial-xml/`; no missing cases are counted as passes.

Concurrent unrelated builds/containers were using substantial memory. They were
not stopped or changed, and the heavier three-FE overlay was not started under
that pressure. The subsequent successful rerun below is separate evidence;
the passing focused/negative/unit checks above were not substituted for it.

### Completed single-FE rerun

With memory headroom restored, `clean build -PwithCancellation` completed in
**15m48s: 383 discovered, 378 passed, zero failures/errors, five overlay-only
skips**. Compilation, detekt, compiled-Trino compatibility and 79-JAR assembly
verification passed. The normal 1536MiB test heap was retained; the Gradle heap
was 1GiB and Kotlin compilation ran in-process, with disk-backed temporary files.

All five skips are the optional `TestDorisMultiFeFailover` class, including its
framework naming check. No combined no-skip result is claimed. Full log/XML:
`r15-full-single-fe-final.log`, `r15-full-single-fe-final-xml/` and
`r15-full-single-fe-summary.json` under the evidence directory above.

### Real-overlay authorization and failover

The overlay was started **after** the full single-FE run, not concurrently with
it. Its four running nodes had the disposable-purpose labels and golden native
versions. With mutation allowed but failover permission absent, all **five
overlay cases skipped**; all four container IDs and start times were unchanged.
This checks the available-cluster case, not merely an absent-overlay skip.

With both permissions explicitly enabled, all **five overlay cases passed,
zero failures/errors/skips**: multi-host access, cross-FE QueryId cancellation,
connector cancellation/release, fresh-connection failover after a follower stop,
and the framework naming check. `trino-doris-mfe-fe1` actually stopped/restarted;
its start time changed, but every original immutable container ID was retained.
All three golden FEs were alive again with exactly one master afterward.

Logs/XML and before/after state: `r15-overlay-without-permission.log`,
`r15-overlay-without-permission-xml/`, `r15-overlay-authorized.log`,
`r15-overlay-authorized-xml/`, `overlay-before-permission.json`,
`overlay-after-no-permission.json`, `overlay-after-authorized.json`, and native
version snapshots. These are staged results, not one all-at-once no-skip build.

```sh
DORIS_TEST_ALLOW_MUTATION=1 ./gradlew clean build -PwithCancellation

# With the optional overlay running, default permission still does not enable it:
env -u DORIS_TEST_ALLOW_FAILOVER DORIS_TEST_ALLOW_MUTATION=1 \
  ./gradlew test --tests '*TestDorisMultiFeFailover'

# Deliberately authorize fixture mutation AND follower lifecycle changes:
DORIS_TEST_ALLOW_MUTATION=1 DORIS_TEST_ALLOW_FAILOVER=1 \
  ./gradlew test --tests '*TestDorisMultiFeFailover'
```

Full-suite and overlay runs used `--no-daemon`, a 1GiB Gradle heap, the normal
1536MiB test heap, in-process Kotlin and the task's disk-backed temp directory.
Guard invocations are defined in `test/qualification/`. Manual startup used its
normal Gradle settings; its resulting idle daemon was individually stopped below.

### Current-plugin smoke and cleanup

After stopping the overlay, a fresh Trino 484 sandbox loaded the verified current
79-JAR snapshot (all hashes equal to assembly inputs). **Nine SQL operations
passed**, including BINARY-mode non-ASCII LIKE fallback and the AVG counterexample.
An independent FE audit lookup by the real Trino query ID confirmed that scan
SQL begins with the statement-token comment. The sandbox ran without either
test-harness permission, confirming they are not production connector switches.
Evidence: `plugin-up.log`, `plugin-smoke.{log,json}`, `plugin-smoke-audit.log`,
and `plugin-snapshot-inventory.json`.

The final permission-refusal/assembly/ABI qualification rerun passed all **four
cases**, including the fresh-report check for the intentionally refused child
fixture run. All **six tooling tests** passed. The seven pure guard tests also
passed in the completed full build, not just their focused run.

Cleanup verified original IDs and ownership before stopping the manual Trino,
single-FE FE/BE and all four overlay containers: **all seven are stopped**.
The idle Gradle daemon created by manual startup was identified by its unique
task tmpdir and last build directory, then stopped individually; no global
`gradle --stop`, container prune, unrelated service stop, or volume deletion was
used. Only this task's temporary working files were cleared; logs, XML,
inventories, fixture data and volumes remain. Evidence: `cleanup-final.json`,
`cleanup-gradle-daemon.log`, `cleanup-temp.log`.

R-08/R-09/R-11/R-17 remain open, along with the separately documented operational
and review risks. Neither completing this scope nor forwarding Unicode evidence
authorizes a release, selects its version, or changes any golden dependency.
