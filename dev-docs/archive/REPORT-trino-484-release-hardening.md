# Trino 484 release hardening

Date: 2026-11-01. Branch/worktree: `trino-484`. Continues the approved batch
after [safety qualification](REPORT-trino-484-release-safety.md), not a release
publication decision. Trino 484 / Doris 4.1.5-release-917b20be838 / Connector/J
26.7.0 / metadata 0.21.0 / Kotlin 2.4.10 / JDK 25 remain fixed.

**Approved hardening batch completed and qualified.** R-06/R-07, R-12–R-14
and R-16 are resolved. The clean single-FE build passed **370 tests** with slow
cancellation enabled, zero failures/errors and five overlay-only skips. Tooling,
actual Gradle guards, archives and current-plugin server smoke also passed.
The six remaining R findings are triaged and stay open; no release version was
chosen, tag created, release branch pushed or GitHub release published.

Implementation milestones pushed to `trino-484`: `15399fe` (R-06/R-07),
`22b1fae` (R-12–R-14), `d57b33b` (R-16 / remaining triage).

## Milestone 1 — R-06 / R-07

All nine published ports across single-FE, three-FE and manual Trino Compose
topologies bind to `127.0.0.1` by default. Sharing requires an explicit
`DORIS_BIND_ADDRESS` or `TRINO_BIND_ADDRESS` override. Documentation recommends
SSH tunnels/authenticated deployments and warns against adding production
credentials to a shared unauthenticated sandbox. This is host-port hardening,
not isolation from Docker peers or local users.

`test/tooling/test_compose_bindings.py` checks six rendered configurations
(three default, three explicit-address overrides). The running golden FE was
recreated with its retained volumes: Docker inspection confirms only
`127.0.0.1:8130` and `127.0.0.1:9130`. No unrelated workloads were changed.

Doris 4.1.5 still rejects `SELECT 'a_b' LIKE '%é_%' ESCAPE 'é'` with
`like escape character must be a single ascii character`. The rule now admits
only a single non-NUL ASCII escape, keeping unsupported escapes local. Durable
regressions compare accented and CJK escapes against
pushdown-disabled Trino in every mode, pin the native rejection, retain a local
filter and check remote SQL. Existing ASCII and no-escape controls remain.
Supplementary escapes are rejected by Trino 484 itself; the test also checks
that the same native Trino error is preserved with pushdown enabled/disabled.

Focused validation: **14 tests passed, zero failures/errors/skips**, plus detekt.
The first disk-backed run found a test assumption error: supplementary escapes
are invalid in Trino itself. The valid-case matrix was corrected to CJK and the
supplementary case retained as an error-parity assertion; no production guard
was relaxed. Final log/XML: `~/.cache/trino-doris-484-hardening/r06-r07-final.log`
and `r06-r07-xml/`. The rendered Compose regression also passes.

## Milestone 2 — R-12 / R-13 / R-14

- `pluginAssemble` is now `Sync` over its **owned versioned output**, with
  duplicate-filename rejection. An inventory-aware up-to-date check also catches
  externally added files. Verification compares exact names and bytes against
  resolved runtime inputs plus the connector JAR, retaining the existing
  parent-first/Guice/ServiceLoader checks. Other versioned directories are not
  silently deleted.
- Version labels must follow `<trino>-<major>.<minor>.<patch>` with an optional
  SemVer prerelease, no build metadata or paths. Gradle rejects mismatched ABI
  labels and checks **resolved Trino compile artifacts**, not just the catalog
  string. `validateReleaseVersion` additionally rejects SNAPSHOT publication.
  The workflow validates before live setup and carries branch-derived values in
  quoted environment variables instead of interpolating them into shell source.
- Manual startup always calls `prepareManualPlugin` (assembly + verification),
  consumes the exact path written by that successful invocation, then snapshots
  it. It never discovers the first versioned directory. A failed verification
  stops startup even if an old path file exists. `--rebuild` forces build tasks
  to rerun but uses the same exact-path contract.

**Three actual-Gradle regression cases passed** (13 invocations): stale-file
rejection and incremental Sync repair without clean/version change, corrupted
JAR rejection/repair, stable/prerelease acceptance, malformed/wrong-ABI/SNAPSHOT
rejection, and an intentionally forced wrong resolved SPI despite a matching
catalog label. The forced SPI is diagnostic-only; no dependency pin was edited.

**Four tooling tests passed**, including all Compose renderings, release-label
injection/malformed cases, and startup orchestration with spaces in the path,
old-before-current directories, both startup modes and failed verification.
Both sets are wired into CI/release as explicit pre-live gates; plain Gradle
`build` does not silently run Python or start Docker.

The real default startup selected the exact current **79-JAR** assembly despite
a deliberately stale sibling directory. Snapshot hashes match every verified
input. Fresh Trino 484 passed **nine SQL operations**, including BINARY-mode
accented/CJK escape results with retained filters, ASCII escape control, and
the AVG counterexample. Docker inspection confirms `127.0.0.1:18080` only.
The manual container was stopped after smoke; volumes/fixtures were retained.

Evidence under `~/.cache/trino-doris-484-hardening/`:
`packaging-guards.log`, `manual-up.log`, `manual-smoke.{log,json}` and
`manual-inventory.json`. Bash syntax and workflow YAML parsing were checked;
no release output remains interpolated into workflow shell source. These are
local workflow-contract checks, not an actual GitHub publication run.

## Milestone 3 — R-16 and remaining triage

Removed both blanket `AVG → SUM/COUNT` recommendations from the README. AVG
continues to execute in Trino. A durable BIGINT 1/2 regression pins average
`1.5` versus integer division `1`; the real-server smoke independently returned
those values. Decimal result types, rounding, overflow and SUM admission do not
inherit an equivalence claim. Related stale README LOWER/UPPER/length and
default-versus-optional test-lane descriptions were corrected while editing.

The [remaining triage](TRIAGE-trino-484-remaining-review.md) covers every open
runtime/configuration/harness R finding: **R-08/R-09/R-10/R-11/R-15/R-17 remain
open**. Five opt-in diagnostic tests passed, reproducing statistics-cache policy
leakage in both directions, false-positive marker classification, duration
normalization/ranges and URL protocol/effective-database gaps. Passing these
diagnostics confirms defects, not release safety. R-09 capacity and R-15 target
authorization remain source-confirmed; no load-throughput or destructive-target
failure was newly exercised.

I recommend resolving R-10 cancellation identity and R-15 destructive harness
authorization before publication, alongside the documented next acceptance
criteria for the remaining configuration and helper-scheduling issues. No fixes
to those six IDs were silently included or declared done by this batch.

Unicode evidence was forwarded to
[brikk-sql#1](https://github.com/brikk/brikk-sql/issues/1), with versioned
counterexamples, column-backed tests and consumer mitigation. Metadata remains
0.21.0, and LOWER/UPPER remain local in every mode. An upstream issue is not
permission to upgrade or re-enable either rule.

## Final qualification

A clean single-FE build with **slow cancellation enabled** completed successfully
in **16m04s**: **375 discovered, 370 passed, zero failures/errors, five skips**
across 50 suites. Compilation, detekt, resolved Trino compatibility and exact
79-JAR assembly verification passed. Existing deprecation warnings remain.
The R-16 regression and all 14 string tests also passed in this full run.

All five skips belong to `TestDorisMultiFeFailover` (four integration cases plus
its framework naming check). The overlay was **not replayed in this batch**;
its port bindings are covered by rendering, and prior safety qualification is
historical evidence, not a new no-skip or cross-FE result. The initial quota
failure and corrected test assumption above are not counted as green runs.

| Check | Completed result |
|---|---|
| Full clean single-FE build, slow cancellation ON | 370 passed / 5 overlay skips / 0 failed or errors |
| Focused string suite + detekt | 14 passed / 0 skipped |
| Tooling contracts | 4 passed (including six rendered Compose configurations) |
| Actual Gradle guard regressions | 3 passed, covering 13 Gradle invocations |
| Open-finding diagnostic replay | 5 passed, confirming defects rather than closing them |
| Real current-plugin Trino 484 smoke | 9 SQL operations passed |
| ZIP/TAR checksum and extraction | Both pass; 79 byte-identical JARs each |

Full-run log/XML and skipped-case inventory are retained under
`~/.cache/trino-doris-484-hardening/`: `full-single-fe.log`,
`full-single-fe-xml/`, and `full-single-fe-summary.json`. Exact local command:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$HOME/.cache/trino-doris-484-hardening/tmp" \
./gradlew clean build -PwithCancellation --no-daemon --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

ZIP and TAR development archives were generated from the fresh verified
assembly. Both SHA-256 sidecars pass `sha256sum --check`; extraction reproduces
all 79 input JARs byte-for-byte. The fresh assembly is also byte-identical to
the snapshot already exercised in the nine-operation real-server smoke. These
are local `484-0.2.0-SNAPSHOT` qualification artifacts, not a selected release
version or published assets. Evidence: `archives.log` and `artifacts/` under the
disk-backed scratch directory.

After qualification, the validation single-FE FE/BE and manual Trino containers
were stopped; volumes, fixture data and local evidence were retained. The
overlay stayed stopped and unrelated workloads were not stopped. Publication
still requires a separate user decision/version and resolution or explicit
disposition of the remaining findings; this report does not authorize it.

## Validation environment

The initial focused attempt failed during Kotlin incremental-cache backup with
`Disk quota exceeded` on the host's `/tmp` tmpfs. This is not an assertion
failure. Its log remains at `/tmp/opencode/trino-484-hardening-r06-r07.log`.
Subsequent runs use disk-backed scratch at
`~/.cache/trino-doris-484-hardening/`, with `JAVA_TOOL_OPTIONS` pointing
`java.io.tmpdir` there and Kotlin compilation in-process. No unrelated temporary
files were deleted. Product dependency pins and test JVM heap are unchanged.
