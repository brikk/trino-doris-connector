# Quality review: 2026-11-09 (Claude Fable)

A full code review asked for after a period of coding with less oversight than usual. The
brief was to find weaknesses and things to improve, with an emphasis on reasonable,
human-readable code.

This version also folds in the two other reviews written the same day,
[QUALITY-REVIEW-2026-11-09-opus.md](QUALITY-REVIEW-2026-11-09-opus.md) and
[QUALITY-REVIEW-2026-11-09-astra.md](QUALITY-REVIEW-2026-11-09-astra.md), and says where each of their findings
stands on the current branch.

## Summary

The design and the core correctness work are sound. The weaknesses are in how the code is
being shipped, in a few pieces of new code, in tests that check a different path from the
one they name, and in readability: the comments and docs read as an agent's audit trail,
not as something written for a maintainer.

All three reviews agree on the readability problems and on the duplicated checks. They
differ in what they looked at: the other two reviewed `main` only, and `main` is not the
current code.

## Scope and method

The recent work is on `trino-484` (19 unmerged commits, 31 Oct to 3 Nov) with a one-commit
port on `trino-483`.

| Line | Commit | Targets | State |
|---|---|---|---|
| `main` | `9871b52` | Trino 483, Doris 4.1.4.1 | Tagged `v483-0.3.0`. Still has bugs the branch fixed. |
| `trino-484` | `f4a1da9` | Trino 484, Doris 4.1.5 | Tagged `v484-0.4.0`. Never run in CI. |
| `trino-483` | `011ad11` | Trino 483, Doris 4.1.5 | Tagged `v483-0.4.0`. Tests compiled, none run. |

What I read:

- All 28 source files on `main`.
- The full source diff from `main` to `trino-484` (26 files, 571 lines added, 244 removed).
- All 48 test files on `main`, the 12 test files the branch adds, and the test diff.
- Build files, CI and release workflows, detekt config and the compose tooling on both lines.

Limits:

- I ran no tests. My findings come from reading code, plus checks against the Trino 484
  and Connector/J 26.7.0 jars in the Gradle cache.
- Findings marked "derived by reading" describe behaviour I expect from the code and the
  driver, not behaviour I observed.
- The Astra review did run things on `main`: live probes for several findings, a
  packaged-plugin smoke, and a full build with the slow cancellation suite (346 tests,
  341 passed, 5 skipped, none failed). Where I rely on its measurements I say so.
- File and line references are to `trino-484` unless marked `(main)`. Run
  `git show origin/trino-484:<path>` to see them from a `main` checkout.

IDs: `R-xx` are from [REVIEW-2026-09-05.md](REVIEW-2026-09-05.md), extended by Astra with
R-18 and R-19. `QR-xx` are from the Opus review. `F-xx` are new in this review.

## Top issues

| # | ID | Issue | Where it applies |
|---|---|---|---|
| 1 | F-01 | The default branch and `v483-0.3.0` give wrong answers that are fixed only on unmerged branches | `main` |
| 2 | F-02 | The current code has never been through CI, and 0.4.0 was published outside the release gate | `trino-484`, `trino-483` |
| 3 | F-04 | Cancellation can amplify load on Doris when queries fail | `trino-484` |
| 4 | F-05 | The new read driver may change where replication URLs read from, and depends on driver internals | `trino-484` |
| 5 | F-08 | Tests check a different path from the one they name, which is how R-18 shipped | all lines |
| 6 | QR-01, QR-02 | Comments are an audit trail, and several are wrong | all lines |
| 7 | QR-03, QR-04 | Hand-copied safety checks have started to disagree | all lines |
| 8 | F-07 | The startup evidence check passes by accident and depends on a false statement | all lines |

## 1. Process

### F-01: `main` still gives known wrong answers

`main` is the default branch and the source of `v483-0.3.0`. Astra reproduced these on it
against live Doris, each compared with pushdown disabled:

- **R-18 (new, default configuration).** `lower`/`upper` pushdown changes results on
  stored columns: `upper('ß')` gives `SS` remotely and `ß` in Trino, and
  `WHERE upper(v) = 'SS'` selects a row remotely that Trino does not.
- **R-04.** `min_by`/`max_by` after a pushed outer join returns `60` where Trino returns
  NULL. This needs join pushdown switched on.
- **R-05.** With a multi-host URL and the helper unavailable, `cancel()` waited 4,006 ms
  for a 4-second query instead of interrupting it.
- **R-07.** A valid non-ASCII LIKE escape becomes a Doris error.
- **R-08.** `statistics_enabled=false` is ignored on a statistics cache hit.

All five are addressed on `trino-484` (see the status table in section 7). None of those
fixes is on `main`.

### F-02: the current code has never been through CI, and 0.4.0 shipped outside the gate

- `ci.yml` triggers only on `main` pushes and pull requests. `dev-docs/VALIDATION.md` on
  the branch says its results are local only.
- Both `v484-0.4.0` and `v483-0.4.0` exist on `origin`. They were published directly with
  the GitHub CLI, not through the release workflow.
- `dev-docs/TRINO-483.md` (on `trino-483`) records this as a maintainer decision and
  states that no tests were run for the 483 build. That commit changes the JSON mapping
  and pairs Connector/J 26.7.0 with a Trino 483 dependency set that expects 9.7.0.

### F-03: two long-lived branches are hand-ported

Three copies of each workflow now exist, and `main` still carries the old release
workflow with no version validation (R-13). A `release-*` branch cut from `main` would
publish old code under any label.

### A note on confidence

41 of the 54 commits on `main` landed on one day (2026-07-19), with "live-proven" claims
throughout. The `lower`/`upper` rule was documented as "CONFIRMED" identical and was
wrong. Treat capitalised certainty in comments as unreviewed until a test of the real
path backs it.

## 2. New code on the branch

F-04 and F-05 are derived by reading.

### F-04: cancellation can amplify load on Doris

- Any scan closed early, and every scan whose query failed, triggers a cancel helper
  (`DorisPreparedStatement.cancel`: the statement started and its results were not
  exhausted).
- If the helper never sees the statement in the process list, it retries every 500 ms for
  15 s, opening a new connection each time: about 30 connections and 30 cluster-wide
  `SHOW FULL PROCESSLIST` calls per scan (`DorisClusterScopedCancel.kt:144-153`).
- A failed query is never seen, so it always takes the full path. During a Doris incident
  the connector adds load exactly when queries are failing.
- No test bounds the probe count; `TestDorisCancelScheduling` asserts only eventual
  release and a lower bound of two probes.
- Capping the number of probes that find nothing would fix it. `BACKLOG.md` calls this
  item (R-09) resolved; I don't think it is. The rework stopped worker threads being
  parked, but moved the cost to connection churn.

### F-05: `DorisReadDriver` may change where replication URLs read from

This is the branch's fix for R-05.

- It pins the physical connection inside `connect()`, before Trino calls
  `setReadOnly(true)` on the read connection. A `replication:` URL would then always read
  from the source host, never from replicas.
- The one replication test (`TestDorisHaReadCancellation.kt:107-112`) sets read-only
  before pinning, the opposite order to production. Every other URL in that class lists
  the same host twice, so a pin to the wrong host could not be detected.
- The class depends on Connector/J internals (`setProxy(null)`, `multiHostSafeProxy`,
  `connectionLock`). Any driver upgrade needs this re-proved.

### F-06: `DorisJdbcConfig` is fragile and hard to read

This is the branch's fix for R-17.

- It fetches the upstream `@Pattern` annotation from `BaseJdbcConfig` by reflection in a
  static initializer. If Trino renames or removes it, catalog creation fails with an
  obscure initializer error.
- Three DNS-SRV entries in `JDBC_TYPES` are unreachable: the upstream pattern
  (`^jdbc:[a-z0-9]+:(?s:.*)$`) cannot match `mysql+srv`, and the tests expect those URLs
  to be rejected.

### F-07: the startup evidence check passes by accident

`DorisPushdownEvidence.verify` asserts that a hand-written description contains the
target function name. All three reviews flag this check (E-16, QR-07).

- For `starts_with` it passes only because the string says "starts_with has no Doris
  equivalent" (`DorisPushdownEvidence.kt:318`). That statement is false, and deleting it
  would stop the connector starting.
- For `length` it passes because `char_length` contains `length`. The rule deliberately
  does not use Doris `length`.
- The test's "deny" canary checks that a function is absent from the evidence list, not
  from the actual rewriter (Astra).
- The check keeps a production dependency (`brikk-sql-metadata` and its serialization
  runtime) and a startup failure mode for something a unit test would do. Opus and I
  would move it to tests. Astra and `BACKLOG.md` (E-28) treat keeping it as a deliberate
  trade-off.

### Still open on the branch from the other reviews

- **QR-10.** Aggregate rules use `verify(...)` and unchecked `as JdbcColumnHandle`
  (`DorisAggregatePushdownRules.kt:114-115`, `:238-239`, `:293-295`, `:352-353`). An
  unexpected shape fails the whole query where it should fall back to Trino.
- **QR-14.** The `doris.time-zone` check is a `require` inside the Guice module
  (`DorisClientModule.kt:171`). It belongs in `DorisConfig` next to the new timeout
  checks, so it reports as a normal configuration error.
- **QR-11.** `runCatching` catches every `Throwable`, including errors
  (`DorisTableStatisticsReader.kt:115`, `:196`). The branch adds a third in
  `DorisReadDriver.kt:49`.
- **CHAR in `BINARY` mode (Astra).** `CHAR_PUSHDOWN` (`DorisTypeMapping.kt:377`) pushes
  CHAR comparisons that are documented as divergent for trailing-space data. A mode
  described as "verified" should keep CHAR local, and leave the unsafe behaviour to
  `FULL`.
- **F-09.** In `DorisArrayWireDecoder.parseElement` (`:160`), the error offset passed to
  `convertQuoted` is read after `parseQuoted` has advanced the position, so a malformed
  DATE, DATETIME or IP element reports the offset of its end, not its start.

## 3. Readability

### QR-01: comment style

Comments are 28% of the source and mostly unreadable without the archive.

- There are about 170 references to internal codes ("PLAN G7.5", "PROBE §3", "SR K11",
  "spike F4", "P2b"). Opus counts 176.
- History is mixed in with the current rules, with heavy capitals and probe results
  inside doc comments.
- 24 comments mention Doris 4.1.3 against a 4.1.5 target.

Opus proposes the rule I'd adopt: state what is true now in one to three plain sentences,
then link one evidence document. Its rewrite of the `supportsAggregationPushdown` comment
is a good model.

### QR-02: comments that are now wrong

The branch fixed several of the entries in the Opus table (array exposure, IP elements,
the cancel-driver URL forms, the `starts_with` prose). These remain:

- `DorisArrayPushdownRules.kt:57` says no boolean-composition rules exist "by design" and
  calls that a correctness invariant; `DorisValueSafeComposition.kt` implements them.
- `DorisClient.kt:403` ("ARRAY until P2") and `:431` ("lands in P1b") describe work as
  upcoming that shipped long ago. `DorisColumnType.kt:45` does the same.
- `DorisArrayElement.kt:37` cites an `AGENTS.md` that is not in the repo.
- `DorisPushdownEvidence.kt:318` still says Doris has no `starts_with` (see F-07).
- Join tests still describe the old pushed behaviour
  (`TestDorisP5JoinPushdown.kt:93-96`, `:107-109`).

Doc comments are also attached to the wrong thing:

- `DorisClient.kt:120-141` has two stacked blocks, the first describing a field 20 lines
  further down.
- In `DorisTypeMapping.kt` the `toTypeHandle` comment sits on the
  `DIAGNOSTIC_JDBC_TYPES` map, and the DATETIME read-path comment sits on
  `timestampType()`.

### QR-03: one native-string check

The "is this a native Doris string column?" check (`varchar`/`string`/`text`) is
hand-copied in four places: `DorisClient.kt:664`, `DorisScalarPushdownRules.kt:115` and
`:359`, `RewriteStringLike.kt:110`.

It is missing from three VARCHAR admissions in `DorisAggregatePushdownRules.kt`
(`min_by`/`max_by` value, `any_value`, `approx_distinct`). A Decimal256, or a MAP or ARRAY
shown as text, can reach them. That probably gives no wrong answer today because the
value passes through, but it is how R-03 happened. One helper used everywhere fixes it.

### QR-04: other copies to merge

- The comparison-operator map appears three times and the operator flip twice
  (`DorisArrayPushdownRules.kt`, `DorisScalarPushdownRules.kt`).
- `DorisValueSafeRewriter` and `DorisScalarRewriterHolder` are the same class. Keep two
  instances; the separation between the tiers is a real safety boundary.
- The domain-value walk is written twice in `DorisTypeMapping.kt`
  (`domainValuesContainNulByte` and `collectDomainSlices`), with a third NUL scan in
  `DorisScalarPushdownRules.kt:152`.
- `unboundedVarcharTextMapping` and `decimal256TextMapping` have identical bodies. The
  decimal argument check is copied (`DorisTypeMapping.kt:211`, `:239`) and so is the
  remote-schema lookup (`DorisClient.kt:341`, `:363`).
- "6" as the maximum datetime precision is spelled four ways
  (`DorisTypeMapping.kt:310-311`, `RewriteDateProjections.kt:132` and `:144`).
- The accepted/rejected DEBUG logging block is repeated three times in `DorisClient.kt`.

### Structure and smaller hygiene

- **QR-05, QR-06.** `DorisClient.kt` scatters rewriter setup between methods and has two
  functions named `isPushableSortKey` (`:510`, `:692`). The `DorisTypeMapping` companion
  mixes type mapping, pushdown controllers, IP encoding and LARGEINT parsing. Astra
  suggests extracting rule registration only; either way, avoid a new rule framework.
- **QR-08.** More than 30 inline fully-qualified names remain, some in files that already
  import the type (`java.sql.Statement` in `DorisClusterScopedCancel.kt:251` and `:267`).
- **QR-09.** `RewriteScalarProjection` never uses its `scalar` parameter
  (`DorisScalarPushdownRules.kt:395-396`).
- **QR-12.** `literalNeedle` returns `Optional<Any>?`, and the `rejected()` helpers return
  a fake value for their side effect.
- **QR-13.** Five static counters in `DorisClusterScopedCancel` and two static rejection
  maps (`REJECTION_COUNTS` in the array and aggregate rules) are readable only through
  DEBUG logs or tests. Remove them or expose real metrics.
- **F-10.** The newest code is the densest: brace-less one-line returns and lines up to
  about 190 characters (`DorisJdbcMetadataFactory.kt`, `DorisJdbcConfig.kt`).
- **F-11.** The docs are written for an agent. `dev-docs/BACKLOG.md` is mostly
  guard-rails such as "No scheduled job, new runner/secret or publication trigger is
  authorized by this backlog", keyed by IDs like E-25 and K-03.

## 4. Tests

### F-08: tests check a different path from the one they name

This is the pattern behind R-18, and it recurs.

- **Constants where stored columns matter (Astra, R-18).** The case-folding test probed
  Unicode adversaries as constants. Doris folds constants the way Trino does and stored
  columns differently, so the tests agreed while real queries did not. The branch adds
  stored-column tests (`TestDorisCaseFoldLocal`).
- **Pushed compared with pushed (Q-10).** The IP TopN test's "local" session disables
  only expression pushdown, and `LIMIT 10` covers a 6-row table
  (`TestDorisIpPushdown.kt:102-110`).
- **Seven meanings of "local" (QR-17).** Helper sessions disable different switches, and
  the narrower ones still push part of the query. Use
  `allow_pushdown_into_connectors=false` unless a test needs something narrower.
- **A cancellation oracle that accepts errors (Astra, R-19).**
  `DorisTestCluster.feLogShowsCancellation` (`:117-126`) merges stderr into stdout,
  ignores the exit code and returns true for any non-blank output. Astra showed it
  returning true for a nonexistent query ID when `docker` failed. It also reads all
  output before applying its 30-second timeout, so a stalled `docker exec` hangs.
- **A timeout test that accepts its own cleanup** (`TestDorisCancellation.kt:145` matches
  `timeout|test cleanup|cancel`).
- **Division done in Java, labelled Trino (Astra, R-16).**
  `TestDorisB2Aggregates.kt:105` computes `sum/count` with `BigDecimal.divide`. The README
  advice is corrected on the branch; this test is not.

### Coverage gaps

- **F-12.** Engine-level cancellation and a timeout that kills a query never run in CI.
  The `cancellation` tag is excluded from the default build and the multi-FE class skips
  (E-29).
- **F-13.** No test asserts that raw-SQL passthrough (`query()`) is unavailable. The
  read-only design depends on it, and nothing fails if the binding is ever added (K-08).
- **F-14.** Some assertions barely constrain. `TestDorisP5Statistics.kt:57` accepts a row
  count anywhere between 0 and 1000 for a 1000-row table.
  `TestDorisMultiFeFailover.kt` "queries through the connector" with a bare `SELECT 1`
  that never reaches Doris.
- **F-15.** The outer-join change left code untested. The `<=>` join rendering in
  `DorisQueryBuilder.kt` no longer has a test, and some join tests now compare two local
  executions.
- **F-16.** `dev-docs/probes/*.kt` are compiled by nothing unless a separate init script
  is passed by hand.
- **No packaged-plugin smoke in the gate (Q-09).** Astra ran one by hand on `main` and it
  passed; nothing automated loads the assembled plugin in a real Trino server.

### Structure

- **QR-15.** Tests are named after project phases. Aggregate tests are spread across
  `P4Aggregates`, `P4AggregateProbes`, `B2Aggregates` and `B3DistinctHaving`. Those class
  names are hard-coded in production strings (`liveProof` in `DorisPushdownEvidence.kt`),
  so renaming a test means editing main source.
- **QR-16.** Helpers are copy-pasted: `provisionFixture` in 16 files, `remoteSql` in 7,
  `rows` in 6, `assertPushedAndIdentical` in 5.
- **QR-18.** Production static counters are asserted with exact equality across six
  files, so tests depend on each other's order. Nine tests also share the JVM-wide
  72-permit semaphore the branch adds.

The new `DorisTestSafety` gate (the fix for R-15) is real: it refuses fixture SQL against
any cluster that isn't the identified disposable one.

## 5. Build and CI

Fixed on the branch: release-version validation (R-13), stale jars in the plugin
directory (R-12), sandbox plugin selection (R-14) and loopback-only dev ports (R-06).

Still open:

- **F-17.** The release job holds a write token while running third-party actions pinned
  to mutable tags, and `ci.yml` has no `permissions` block (E-32).
- **F-18.** detekt is an alpha version (`2.0.0-alpha.3`) and probably runs without type
  resolution. To confirm, run `./gradlew detektMain detektTest` and compare the counts.
  `config/detekt/detekt.yml` is the default file with five changes and a comment naming
  another project (`JdbcDucklakeCatalog`).
- **F-19.** The branch default version says `484-0.2.0-SNAPSHOT` after 0.4.0 shipped.
- **F-20.** The new CI steps have never run on a GitHub runner. The Python guard tests
  under `test/qualification` add more than a dozen Gradle invocations inside an unchanged
  30-minute timeout.

## 6. What is good

- Read-only enforcement is layered and tested by reflection against the whole
  `JdbcClient` surface. None of the three reviews found a write escape.
- Type mapping is careful about what the wire format can and cannot represent, and fails
  loudly when it cannot.
- Pushdown tests mostly compare against a pushdown-disabled run over adversarial data
  (NUL bytes, year 0 and 9999, LARGEINT extremes, NaN and infinities).
- The September findings were worked through and fixed on the branch, not argued away.
- The full suite passed on `main` in Astra's run, including the slow cancellation tests.

## 7. Status of the September findings by line

The other two reviews list R-04 to R-17 as open. That is right for `main`. On `trino-484`
the picture is different. The branch column is from reading the diff; I did not run the
branch's tests.

| ID | Issue | `main` | `trino-484` |
|---|---|---|---|
| R-04 | `min_by`/`max_by` after an outer join | Open, reproduced | Outer joins kept local |
| R-05 | Cancel blocks on the multi-host proxy lock | Open, reproduced | Addressed by `DorisReadDriver`; see F-05 |
| R-06 | Dev ports on all interfaces | Open | Fixed |
| R-07 | Non-ASCII LIKE escape | Open, reproduced | Fixed |
| R-08 | Statistics switch bypassed by the cache | Open, reproduced | Fixed above the cache |
| R-09 | Finished scans occupy cancel workers | Open | Partly; see F-04 |
| R-10 | Cancel marker matched as a substring | Open | Fixed: marker leads the SQL, matched as a prefix |
| R-11 | Timeout ranges not validated | Open | Fixed (`DorisTimeouts`) |
| R-12 | Stale jars in the plugin directory | Open, reproduced | Fixed |
| R-13 | Release label not validated | Open | Fixed on the branch only; see F-03 |
| R-14 | Sandbox may pick an old plugin directory | Open | Fixed |
| R-15 | Destructive tests not gated on the target | Open | Fixed (`DorisTestSafety`) |
| R-16 | README `avg` advice | Open, reproduced | README fixed; the test is not (F-08) |
| R-17 | URL validation too broad | Open | Fixed; see F-06 |
| R-18 | `lower`/`upper` pushdown wrong on stored columns | Open, reproduced | Fixed: both stay in Trino |
| R-19 | Test harness subprocess helpers | Open, reproduced | Open |

## Suggested order

1. Open pull requests from `trino-484` and `trino-483` so CI runs, then make one of them
   the default branch (F-01, F-02, F-03).
2. Run the suite against the published `v483-0.4.0` build.
3. Bound the cancel probes (F-04) and add the replication-routing test (F-05).
4. Repair the test oracles in F-08, starting with R-19 and the IP TopN test.
5. Merge the duplicated checks (QR-03, QR-04). That is a correctness fix as much as a
   cleanup.
6. Do a comment pass file by file (QR-01, QR-02): delete history and codes, keep the
   present-tense reason. Small commits per file let a reviewer confirm only comments
   changed.
7. Move the evidence check into a test (F-07), then rename tests by feature and share
   their helpers (QR-15, QR-16).
