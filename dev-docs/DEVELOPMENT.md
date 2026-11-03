# Development

## Toolchain and baseline

- JDK 25; `mise install` uses the repository's tool definition.
- Gradle wrapper; do not substitute a system Gradle version.
- Python 3.11+ for the standard-library tooling/qualification tests.
- Docker with Compose v2 for live integration tests.
- Branch target: Trino 483 and Doris 4.1.5-release-917b20be838, using the checked-in
  FE/BE release digests. Connector/J 26.7.0, metadata 0.21.0 and Kotlin 2.4.10 stay pinned.
  A version change needs a separate request and qualification, not a rolling image update.

The [483 adaptation](TRINO-483.md) reuses the completed 484/Doris qualification
by explicit maintainer decision. Commands below remain development references;
no test/live suite is rerun for the paired 0.4.0 publication.

Run commands from the repository root. `mise exec --` supplies JDK 25 when it is
not already selected by the shell.

## Build without a live cluster

```sh
mise exec -- ./gradlew classes testClasses detekt --no-daemon
mise exec -- ./gradlew verifyPluginAssembly --no-daemon
```

`prepareManualPlugin` additionally writes the exact verified output path to
`build/manual-plugin-path.txt`. It does not start a server. The assembly is an
owned, synchronized directory of JARs, not a shaded JAR.

Pure unit examples need neither Docker nor live-test permissions:

```sh
mise exec -- ./gradlew test --no-daemon \
  --tests '*TestDorisTimeouts' --tests '*TestDorisJdbcUrlValidation' \
  --tests '*TestDorisStatisticsPolicy' --tests '*TestDorisTestSafety'
```

## Single-FE live tests

```sh
./compose/up.sh
DORIS_TEST_ALLOW_MUTATION=1 mise exec -- ./gradlew build --no-daemon
```

The stack is `trino-doris-dev`, with FE MySQL at `127.0.0.1:9130`, HTTP at
`127.0.0.1:8130`, and a passwordless root account. It is disposable, not a secure
shared database. Published ports bind to loopback by default; Docker peers and
local users are not isolated by that binding. `DORIS_BIND_ADDRESS` is an explicit
sharing override, not permission to run destructive tests.

**Tests DROP/rebuild fixture schemas and recreate accounts.** A failed or
mismatched `p0_probe` fingerprint can rebuild that database; other suites reset
their own schemas. Complete base fixtures are reused, not guaranteed preserved
under every failure. Never point the fixture harness at production.

`DorisTestSafety` checks the permission before Docker/socket/SQL work, verifies
the disposable label, Compose ownership, original container IDs, image family,
network/port layout and the native FE/BE roster/versions/ClusterId. Each connection
is checked before callers receive it. Fixed names/ports or a matching version
alone are insufficient. Older containers without labels must be recreated using
the checked-in Compose files; ordinary `up.sh` preserves their volumes.

This is an accidental-target guardrail, not a sandbox against someone controlling
Docker, labels, SQL metadata and routing. Do not add arbitrary endpoint overrides
that bypass it. The test flags cannot enable writes through the production connector.

## Slow cancellation and the separate overlay

The default Gradle/CI suite excludes the slow cancellation tag. Run it explicitly:

```sh
DORIS_TEST_ALLOW_MUTATION=1 mise exec -- ./gradlew clean build -PwithCancellation --no-daemon
```

The separate `trino-doris-mfe` overlay has three FEs and one BE; FE MySQL ports
are 9131/9132/9133 and HTTP ports 8131/8132/8133, loopback-only by default.
The overlay runner also uses the single-FE base fixture cluster, so keep both up
for this targeted lane:

```sh
./compose/multi-fe/up.sh
DORIS_TEST_ALLOW_MUTATION=1 DORIS_TEST_ALLOW_FAILOVER=1 \
  mise exec -- ./gradlew test --tests '*TestDorisMultiFeFailover' --no-daemon
```

Without failover permission, the optional class skips without probing ports—even
if the overlay is available. With an explicit request, an unsafe/unavailable
target fails loudly. Stop/start validates ownership and uses original immutable
IDs, refusing unknown or replaced containers. A non-master follower is restored
after its failover test. Both permission flags are Gradle test inputs.

Run heavy lanes separately on shared hosts. An OOM/interrupted run is not green
qualification; retain its logs and rerun with headroom. The normal test heap is
1536MiB. A warm-cache qualification can use a smaller Gradle process without
changing the test heap:

```sh
DORIS_TEST_ALLOW_MUTATION=1 mise exec -- ./gradlew clean build -PwithCancellation --no-daemon \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

## Tooling and release-contract checks

```sh
python3 -m unittest discover -s test/tooling -v
python3 -m unittest discover -s test/qualification -v
```

These are existing explicit CI gates. They cover Compose bindings/labels,
centralized safety boundaries, release-label grammar, exact manual plugin
selection and actual Gradle assembly/ABI guards. The permission-refusal test
deliberately removes both flags from a child fixture run and requires a fresh
report proving refusal at the authorization boundary; a generic connection or
compilation failure is not accepted as proof.

## Diagnostics and cleanup

JUnit XML/HTML live under `build/test-results/` and `build/reports/tests/`.
FE audit SQL and processlist/QueryIds support the pushdown/cancellation oracles.
Use the [probe guide](probes/README.md) for targeted replays, not the archived
commands as an assumed current release gate.

Stop the owned stacks when finished; retain volumes unless deletion is intended:

```sh
docker compose -f compose/multi-fe/docker-compose.yml stop
docker compose -f compose/docker-compose.yml stop
```

`up.sh --down`/`down.sh` delete that stack's volumes. Do not use them as a
volume-preserving cleanup shortcut. On shared hosts, do not globally prune
containers, stop unrelated services, or stop other builds' Gradle daemons. Prefer
`--no-daemon`; any daemon created for this work must be identified before stopping.

The [manual sandbox](../compose/trino/README.md) validates the real plugin
classloader. Test helpers/programmatic plugin installation do not replace that proof.
