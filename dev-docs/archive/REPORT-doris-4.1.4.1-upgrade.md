# Doris 4.1.4.1 upgrade qualification

Date: 2026-10-10. Trino 483, Connector/J 9.7.0, JDK 25.0.2.

**Outcome:** Compose/CI now target final Doris 4.1.4.1. The last full run passed
345/346 tests with zero skips; the only failure was the known, production-unused
trace-ID cancellation pin, which passed in isolation. Production QueryId
cancellation, multi-FE tests, hotfix pins, detekt, assembly verification, and the
real-server smoke passed. **No full-suite green result is claimed.**

## Release identity and scope

- [4.1.4.1 final release](https://github.com/apache/doris/releases/tag/4.1.4.1),
  published 2026-09-29; GitHub marks it neither draft nor prerelease.
- [4.1.4 notes](https://github.com/apache/doris/issues/67355) and
  [4.1.4.1 hotfix notes](https://github.com/apache/doris/issues/68518).
- The annotated `4.1.4.1` Git tag resolves to
  `5dbec55b399c2c4323f565bb2c6745cacb574416`.
- All four FEs and both BEs report `doris-4.1.4.1-rc02-5dbec55b399`.
  The retained `rc02` label is in the published final images; the commit matches
  the final tag. `SELECT VERSION()` is still the MySQL compatibility string
  `5.7.99`, not the Doris release identity.
- Main and multi-FE Compose files now use the same verified multi-architecture
  release-index digests. Both indexes contain amd64 and arm64 manifests:

| Component | Image | Index digest |
|---|---|---|
| FE | `apache/doris:fe-4.1.4.1` | `sha256:a9da001c8afa6d07b9b3de532a0b65566c3bc134f0beefbedfce7fad006eb843` |
| BE | `apache/doris:be-4.1.4.1` | `sha256:0b2827e2fcc0fc9b007502bda2a89e00d97f5696ca47c89f95cdff8e488da249` |

Runtime qualification uses Linux/amd64. ARM64 availability is verified from the
registry, not runtime-tested. Historical 4.1.3 reports, raw evidence, source links,
and dated review results are not relabeled as 4.1.4.1 evidence.

## Compatibility checks

The 4.1.4 notes touch complex hash keys, grouping, aggregate nullability, CHAR
MIN/MAX, TopN, string casts, floating-point ZoneMaps, and query teardown. These
changes do not establish equivalence to Trino or remove connector safety gates.
In particular:

- Keep R-03's native-string provenance checks for grouping and TopN: Doris-native
  complex equality/order is not equality/order of the VARCHAR exposed to Trino.
- Keep R-02's simple dotted JSON path restriction and R-01's assignment-aware date
  projection resolution.
- Keep FLOAT/DOUBLE and aggregate exclusions; ZoneMap representation changes do
  not prove lossless MySQL-wire transport or identical NaN/overflow/AVG semantics.
- TIMESTAMPTZ, Arrow Flight, writes, and new Doris feature families remain outside
  this connector's enabled contract.
- Review items R-04 through R-17 remain open. An upgrade does not fix connector-side
  outer-join nullability provenance, socket-abort locking, or other review findings.

The production `brikk-sql-metadata-jvm` dependency remains at 0.7.0 for a
single-variable Doris comparison. A user-requested release check found Maven
Central's latest release is 0.18.0: its published `GeneratedTrinoDorisHazards.kt`
source is byte-identical to 0.7.0, and the existing registry lookup API is retained.
The newer POM declares Kotlin 2.4.10 and serialization 1.11.0, so compiler/runtime
compatibility and the connector's version/JAR-SHA pins require a separate upgrade
check; source inspection alone is not a tested dependency upgrade.

`TestDorisReleaseCompatibility` adds a hotfix-specific pin for Doris #68401:
100,000 rows with nullable JSON Boolean OR inside multi-branch CASE, short-circuit
evaluation both enabled and disabled, exact branch counts (66,000 / 34,000), and
reading the remote CASE view through Trino. It also compares all nine nullable OR
truth-table combinations with Trino. CASE/IF pushdown remains engine-denied;
the remote view exercises Doris execution without broadening connector admission.

## Persisted-cluster restart

The single-FE FE/BE upgraded on existing named volumes. The original cluster ID
`1945291672` and backend ID `1788328290997` were retained.

The overlay initially failed: Compose gated followers on FE1's health, while FE1's
persisted three-member BDBJE metadata required a quorum before it could become
healthy (`feType:UNKNOWN`, catalog not ready). This is a startup-order defect in
the existing development harness, not evidence of incompatible Doris data.

Follower dependencies now require FE1 to be **started**, not healthy. Fresh
followers already wait/retry for registration in the stock image's `init_fe.sh`;
persisted followers can start immediately to restore quorum. Health gates now
read named `Alive` / `IsMaster` / `Role` fields instead of arbitrary `true` cells.
After stopping and restarting the whole overlay with the updated script, all three
FEs were alive, exactly one was master, the BE was alive, and all three host ports
accepted SQL. Overlay cluster ID `1883100475` and backend ID `1788829064911` were
retained. No volumes were deleted; fresh-volume bootstrap has not been rerun.

## Validation record

Validation is complete with the trace-ID pin caveat below. Existing review
findings and the recorded cancellation limitations remain unresolved; this is
not a claim that the connector or Doris has no issues.

Previous 4.1.3 comparison run (2026-09-08): **343 tests, 342 passed, one failed,
zero skips**. `TestDorisMultiFeFailover.testCrossFeKillByQueryIdReleasesForeignFeQuery`
timed out after a single cross-FE KILL. Preserve this failure rather than calling
the baseline green. Baseline XML was copied before cleaning the build.

Full 4.1.4.1 command, with main and overlay clusters running:

```sh
mise exec -- ./gradlew clean build -PwithCancellation --rerun-tasks --no-daemon
```

Initial 4.1.4.1 run: **346 tests, 342 passed, four failed, zero skips**;
compilation, detekt, and assembly verification passed. The four failures were:

1. Two FLOAT pins retained the old 4.1.3 reduced-precision expectations. Direct
   wire inspection now returns `3.4028235e+38` / `-3.4028235e+38` for the stored
   extrema; parsing to REAL preserves bits `0x7f7fffff` / `0xff7fffff`. Widening
   before transport agrees. The tests now pin this verified improvement. Other
   sample values, infinities, NaN, signed zero, and the smallest subnormal retain
   their existing behavior. No approximate-type pushdown exclusions are relaxed.
2. The baseline cross-FE `sleep(60)` timeout persisted. FE3's audit shows KILL was
   accepted at 19:46:16.359 and FE2's log records coordinator cancellation then,
   but the sleeping BE work delayed result release until 19:47:16.574. The
   test now uses the existing cancellable cross-join scan fixture, validates the
   assigned QueryId, verifies FE2 ownership from FE3, and requires the server's
   `cancel query by user` error rather than a socket timeout. This corrects the
   test workload; it does not claim Doris's sleep cancellation delay or connector
   socket-abort limitations are fixed.
3. The new CASE pin had correct row values but used bounded VARCHAR expected
   literals against the connector's unbounded VARCHAR view mapping. Explicit
   expected VARCHAR casts fix only that assertion's type contract.

Focused rerun: **12 tests passed, zero failures/skips**, plus detekt and assembly
verification (79 jars, no forbidden parent-first SPI jars, ServiceLoader present):

```sh
mise exec -- ./gradlew test --tests '*TestDorisFloatSpecialValues' \
  --tests '*TestDorisMultiFeFailover' --tests '*TestDorisReleaseCompatibility' \
  detekt verifyPluginAssembly --no-daemon
```

Second clean full run: **346 tests, 345 passed, one failed, zero skips**. The
remaining failure was the scan-workload cross-FE pin issuing just one KILL. Audit
timestamps show KILL accepted 4 ms after submission, before its 22 ms planning
phase completed; the query later finished naturally after 115.899 seconds. This
reproduces the assigned-QueryId-before-coordinator race the production helper
already handles with bounded retries.

The cross-FE pin now uses that same **15-second retry window**, followed by a
5-second victim-result wait, rather than trusting a single acknowledged KILL.
All attempts still originate from FE3 against FE2's QueryId, and success must be
the server's cancellation error, not completion or a client timeout. This changes
only the test, not production cancellation. The **five-test multi-FE suite passed
three consecutive reruns** after this change; detekt and assembly verification
also passed.

Third clean full run: **45 suites, 346 tests, 345 passed, one failed, zero errors,
zero skips**. All multi-FE, FLOAT, nullable OR/CASE, and production cancellation
tests passed. `TestDorisClusterScopedCancel.testTraceIdKillBehaviorPin` timed out
after its one-shot trace-ID KILL against a sleeping query. The same pin timed out
in the historical 4.1.3 review; it is intentionally version-sensitive and the
production connector does not use trace IDs for cancellation. This test is left
unchanged, not disabled or reclassified as a success.

Its isolated rerun **passed**:

```sh
mise exec -- ./gradlew test \
  --tests '*TestDorisClusterScopedCancel.testTraceIdKillBehaviorPin' --no-daemon
```

That rerun does **not** make the full run green. All three full-run logs and XML
results are retained separately, including the failing third full run. Compiler
output contains the existing Trino deprecated-API warnings; no new compilation
errors, detekt findings, or assembly failures were observed.

Baseline, all three full runs, and initial focused XML were copied before
reruns so their outcomes remain inspectable. The updated topology parsers also
passed seven synthetic cases, including rejecting dead nodes whose other Boolean
fields are true, duplicate masters, and empty results.

### Real-server assembled-plugin smoke

Passed using the sole clean-build assembly
`build/trino-plugin/trino-doris-483-0.2.0-SNAPSHOT`, snapshotted by
`./compose/trino/up.sh` and loaded into `trinodb/trino:483` via ServiceLoader:

- `CREATE CATALOG doris_validation USING doris`, schema/table discovery, and
  `DROP CATALOG` succeeded.
- `count(*)` of `p0_probe.scalars_view` returned 8.
- The selective `nums` predicate returned 1000996 through 1000999.
- EXPLAIN of the predicate + ordered LIMIT 2 showed a single TableScan with
  `constraint on [n]`, `sortOrder=[... ASC NULLS LAST]`, and `limit=2`;
  no local Filter, Sort, or TopN remained.
- The remote CASE view returned `then_a:66000`, `then_b:34000`.
- Stored FLOAT extrema read as `3.4028235E38` and `-3.4028235E38`.

This validates the assembled directory/classloader path, not extraction or
checksums of a published connector archive. The smoke container is stopped.
Local logs, image identities, per-FE topology/version snapshots, smoke output,
and baseline/full/focused XML are retained under
`/tmp/opencode/doris-4.1.4.1-validation/`.

The unrelated `doris-ducklake-fe` and `doris-ducklake-be` were temporarily stopped
with explicit user permission to free memory. The manual Trino, single-FE, and
multi-FE validation containers are now stopped; **no volumes were deleted**.
Restoring the two ducklake containers was attempted but Docker reported both
names no longer existed. Other work removed them during validation and also
stopped the PostgreSQL/MinIO stack. That intervening state is left untouched:
no unrelated containers are recreated, and this session did not stop PostgreSQL
or MinIO. Ducklake restoration therefore remains unperformed.
