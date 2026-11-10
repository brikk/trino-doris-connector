# Quality review — Astra — 2026-11-09

Baseline: `9871b52`, clean `main`. Reviewed all 28 production Kotlin files, test
coverage and helpers, Gradle configuration, CI/release workflows, compose scripts,
and the existing design/review evidence. Trino 483, Connector/J 9.7.0, JDK 25.0.2,
and the repository's digest-pinned Doris 4.1.4.1 images.

## Assessment

The architecture is appropriate and the type/read-only work is substantial. The
main problem is that correctness claims exceed what the tests actually establish.
There are still small, valid queries that return different answers with pushdown.
**A newly confirmed Unicode case-folding defect affects the default configuration.**

Readability needs a focused cleanup: present-day invariants are buried in historical
prose, a few semantic checks are repeated independently, and some tests check a
different execution path from the one they claim to verify. A broad rewrite would
add risk; small correctness fixes and simpler local contracts would be more useful.

Existing IDs below refer to [the September review](REVIEW-2026-09-05.md).
R-01/R-02/R-03 fixes are present. R-04 through R-17 remain actionable in the current
source. R-18 and R-19 are added here; previous IDs are not renumbered.

## Highest-priority findings

### R-18 — P1: Unicode `lower`/`upper` pushdown changes results by default

**Location:** `src/dev/brikk/trino/doris/DorisScalarPushdownRules.kt:216-242`;
registration at `DorisClient.kt:149-150`.

`RewriteCaseFold` admits all native VARCHAR/STRING column values in every string
mode. Doris's stored-column evaluation does not preserve Trino's case mapping.
Fresh comparisons with `allow_pushdown_into_connectors=false` found:

| Stored value | Expression | Pushed result | Local Trino result |
|---|---|---|---|
| `ß` | `upper(v)` | `SS` | `ß` |
| `İ` (U+0130) | `lower(v)` | `i` + U+0307 | `i` |
| `ΑΣ` | `lower(v)` | `ας` (final sigma) | `ασ` |

This is also a filtering defect: `WHERE upper(v) = 'SS'` selected the `ß` row
remotely and selected no rows locally. Grouping on these transformations can
likewise change groups. No opt-in setting is required.

**Why existing tests miss it:** `TestDorisB1ScalarPushdown.kt:172-192` probes the
Unicode adversaries as constants. On the same server, direct
`SELECT upper('ß'), lower('İ'), lower('ΑΣ')` returned `ß`, `i`, `ασ`—agreeing with
Trino while disagreeing with the stored-column path. Constant-folded agreement is
not evidence for backend column evaluation. The ordinary column tests use a
different input set. The KDoc and README's identity claim are therefore false.

A broader stored-string probe also found Unicode-version-related differences,
including U+1C89 lowercasing differently. Its input round-tripped unchanged; this
was not a JDBC decoding loss.

**Fix:** keep these transformations local until their actual column execution is
equivalent. Add stored-column regressions for projections, filters, and grouping,
covering bounded VARCHAR and STRING. Compare against explicit expected values and
pushdown-disabled execution. A literal-only guard or a larger constant-expression
battery cannot fix a data-dependent column mismatch.

Minimal reproduction (create the table directly in disposable Doris):

```sql
CREATE TABLE review_case (id INT NOT NULL, v STRING)
DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1
PROPERTIES ('replication_num'='1');
INSERT INTO review_case VALUES (1, 'ß'), (2, 'İ'), (3, 'ΑΣ');
```

Then in Trino, using the appropriate catalog/schema:

```sql
SELECT id, lower(v), upper(v) FROM doris.review_schema.review_case ORDER BY id;
SELECT id FROM doris.review_schema.review_case WHERE upper(v) = 'SS';
SET SESSION allow_pushdown_into_connectors = false;
-- Repeat both SELECTs: results differ.
```

### R-04 — P1: Outer joins invalidate the `min_by`/`max_by` nullability guard

**Location:** `DorisAggregatePushdownRules.kt:291-320`, especially `:308`;
join admission at `DorisClient.kt:262-277`.

The guard trusts the base column's NOT NULL flag. Trino 483's
`DefaultJdbcMetadata.createSyntheticJoinProjectionColumn` copies that flag even
when an outer join introduces NULLs. Doris skips NULL payloads; Trino can select
them. Fresh reproduction: `l.id = {1,2,3}`, `r = {(2,60)}`, `r.v NOT NULL`:

```sql
SELECT min_by(r.v, l.id), max_by(r.v, l.id)
FROM l LEFT JOIN r ON l.id = r.id;
```

With eager join pushdown: `(60,60)`. With pushdown disabled: `(NULL,NULL)`.
Join pushdown is off by default, so this requires opting in.

**Fix:** preserve effective nullability through join outputs, or reject these
aggregates where that provenance is unavailable. Test LEFT/RIGHT joins and
unmatched winning keys, while retaining valid base-table cases.

### R-05 — P1: Multi-host cancellation's fallback blocks on execution

**Location:** `DorisPreparedStatement.kt:65-85,95-101`;
normal connections at `DorisClientModule.kt:112-115`.

`connection.abort(directExecutor())` synchronously enters Connector/J's HA proxy.
That proxy acquires the same lock held by statement execution. Fresh probe using
the production wrapper, a comma-list URL, `SELECT sleep(4)`, and a disabled helper:
`cancel()` took **4,006 ms** after processlist visibility. The earlier run took
4,012 ms. It waited for execution rather than interrupting it.

A successful helper kill can release the lock, but helper failure/disablement is
precisely when an independent socket fallback is needed. `close()` additionally
waits uninterruptibly for cancellation completion.

**Fix:** establish a verified physical abort target before execution and test the
real HA proxy with disabled/failing helpers. Unwrapping the busy proxy during
cancellation is insufficient. Preserve statement isolation and the prohibition
on Connector/J's connection-ID kill path.

## Other production correctness and reliability findings

### R-07 — P2: Valid non-ASCII LIKE escapes become remote errors

**Location:** `RewriteStringLike.kt:85-95`.

The rule checks only for NUL and forwards the escape. Fresh BINARY-mode query
`v LIKE '%é_%' ESCAPE 'é'` failed with “like escape character must be a single
ascii character”; local execution correctly selected `a_b`.

**Fix:** reject unsupported escape characters or translate to a proven compatible
escape convention. Test actual column predicates with non-ASCII escapes.

### R-08 — P2: Statistics disablement does not apply to cached results

**Location:** `DorisClient.kt:333-350`; `DorisSessionProperties.kt:55-59`.

The switch is inside the delegate, below Trino's `CachingJdbcClient`. That cache
keys statistics by table handle and returns hits without invoking the switch.
Fresh positive-TTL reproduction (`metadata.statistics.cache-ttl=5m`): after an
enabled `SHOW STATS`, a session with `statistics_enabled=false` still received
row count 1 and populated column estimates.

**Fix:** enforce policy above the cache or incorporate policy into cache identity.
Test both enabled-to-disabled and disabled-to-enabled transitions, including
missing-value caching. Default zero cross-query TTL limits exposure.

### Remaining source-confirmed defects

Paths in this table are relative to `src/dev/brikk/trino/doris/` unless specified.
These were rechecked in current source; they are not fresh load-test measurements.

| ID | Priority | Location | Weakness and focused remedy |
|---|---|---|---|
| R-09 | P2 | `DorisClusterScopedCancel.kt:163-186,233-237` | A never-observed marker consumes a helper worker/connection until the 15-second deadline even if execution finished. Eight such requests occupy every worker; queued real cancellations can expire. Preserve delayed-visibility protection using scheduled retries rather than sleeping in scarce workers. |
| R-10 | P2 | `DorisClusterScopedCancel.kt:277-287` | Substring matching treats `SELECT '/*trino_statement_id=<token>*/'` as an owned statement. A monitoring query containing the comment as text can be killed. Match comment context/placement, not arbitrary SQL substrings; test quoted markers. No collateral kill was attempted. |
| R-11 | P2 | `DorisConfig.kt:37-43,117-123`; `DorisClientModule.kt:159,166`; `DorisClient.kt:448-450` | Timeout ranges are not validated. Zero/submillisecond connect durations become an unbounded driver timeout; very large values exceed its integer range. A subsecond query timeout can become zero at connection initialization but one second at scan setup. Share validated normalization across catalog and session settings. |
| R-17 | P3 | `DorisJdbcConfig.kt:29-44` | `acceptsURL` is broader than supported JDBC operation, and checking only URL path misses effective `DBNAME` properties. Validate supported protocol and resolved database options. This is a configuration-contract issue, not a demonstrated write escape. |

`BINARY` also deliberately permits known-incorrect CHAR comparisons
(`DorisTypeMapping.kt:376-387`). That is documented behavior, not a newly discovered
bug, but a mode advertised as “verified” should ideally keep CHAR local. Separate
verified VARCHAR semantics from caller-accepted unsafe behavior.

## Build, release, and development tooling

| ID | Priority | Location | Weakness and focused remedy |
|---|---|---|---|
| R-12 | P2 | `build.gradle.kts:137-142,151-181` | `Copy` assembly leaves removed/old dependency jars behind under the same version. Verification checks selected prefixes, not the complete resolved inventory. Use `Sync` and check the exact jar set. |
| R-13 | P2 | `.github/workflows/release.yml:47-60,96-104,114-115` | Any nonempty release suffix is accepted, even one claiming another Trino ABI. Branch-derived output is interpolated into shell source. Validate the version grammar and actual Trino dependency; use quoted environment variables for shell arguments. |
| R-14 | P2 | `compose/trino/up.sh:41-64` | `find ... \| head -n1` may select an old plugin directory even after `--rebuild`. Select the exact build output or fail on ambiguity, and run assembly verification. |
| R-06 | P2 | `compose/docker-compose.yml:29-31`; multi-FE and manual-Trino compose ports | Passwordless Doris and unauthenticated manual Trino publish on all host interfaces. Default development bindings should be loopback. Elevate urgency on network-shared hosts; external reachability was not tested. |
| R-15 | P2 | `test/.../DorisFixtures.kt:85-105`; `TestDorisMultiFeFailover.kt:100-108` | A failed row-count fingerprint can trigger dropping `p0_probe`; other suites drop fixed schemas, and the overlay test stops a container based on endpoint availability. Establish disposable-target identity and explicit failover enablement at the common test boundary. |
| R-19 | P2 | `test/.../DorisTestCluster.kt:94-116` | Both subprocess helpers call blocking `readText()` before `waitFor(30, ...)`. If `docker exec` stalls while keeping stdout open, the advertised timeout is never reached. Read asynchronously or redirect output, enforce the deadline first, and terminate timed-out processes. This is a test-harness hang, not a connector query bug. |

**R-12 fresh experiment:** placed one valid, empty `review-stale-0.0.jar` in the
assembled output, then ran `pluginAssemble verifyPluginAssembly`. Assembly left it
present and verification succeeded with 80 jars. Removed only that synthetic jar
afterward. This confirms the output-inventory gap without changing dependencies.

R-19 also weakens the cancellation oracle: `feLogShowsCancellation` merges stderr
into stdout, ignores the exit code, and returns true for any nonblank output.
**Fresh controlled reproduction:** invoking the actual helper with a child-process
PATH containing a fake `docker` that writes a daemon error to stderr and exits 1
returned `true` for a nonexistent query ID. Check process success and distinguish
an empty grep result from a failed invocation. The blocked-pipe timeout case above
is source-derived; this false-positive case was executed.

### R-16 — P2: The README's `avg` replacement is wrong

`README.md:81,135-138` recommends `sum(x)/count(x)` as exact and pushable.
Fresh Trino evaluation over BIGINT values 1 and 2 returned `avg(x)=1.5` but
`sum(x)/count(x)=1`. Integer SUM is also deliberately not pushed by this connector.
Remove the blanket recommendation; any decimal-specific advice needs a result
type, rounding, overflow, and empty-input contract.

The supporting test at `TestDorisB2Aggregates.kt:98-106` computes the division in
Java using `BigDecimal.divide(..., HALF_UP)`, then labels it Trino division. Test
the advertised SQL expression in Trino itself.

## Human-readable code: worthwhile changes

1. **Replace implementation history with current invariants.**
   `DorisClient.kt` has 203 comment-only lines in 704; `DorisTypeMapping.kt` has
   170 in 740. Comment volume alone is not a defect, but many comments are now false:
   arrays are described as future work (`DorisTypeMapping.kt:91-92`), IP arrays as
   excluded (`DorisArrayPushdownRules.kt:79-80,430-431`), and supported HA URL forms
   as rejected (`DorisCancelDriver.kt:29-33`). Keep the reason for a restriction
   beside its check; move chronological narratives to linked dated reports.

2. **Deduplicate specific invariants, not every similar-looking rule.**
   Comparison maps/flips repeat in `DorisArrayPushdownRules.kt:396-412,482-497`
   and `DorisScalarPushdownRules.kt:320-327`. Native-string checks repeat in the
   client, scalar leaf, starts-with, and LIKE rules. Domain-Slice collection is
   duplicated in `DorisTypeMapping.kt:412-424,595-607`. Small named helpers make
   later fixes consistent. Preserve the distinction between predicate-only and
   value-safe expression tiers; it encodes a real correctness boundary.

3. **Simplify unnecessary API shapes.**
   `literalNeedle` returns `Optional<Any>?`, but success always contains a value
   (`DorisArrayPushdownRules.kt:109-130`): use one absence representation internally.
   Remove the unused `scalar` constructor field in `RewriteScalarProjection`
   (`DorisScalarPushdownRules.kt:424-436`). Prefer normal imports over incidental
   fully qualified names. Extract rule-registration methods from `DorisClient`
   if needed to make the wiring readable; a new generic rule framework is unnecessary.

4. **Make evidence tests test executable behavior.**
   `DorisPushdownEvidence.kt:371-374` checks handwritten rendering text, not emitted
   SQL. `TestDorisPushdownEvidenceDrift.kt:78-104` checks that a named class exists,
   not that it tests the rule's guards. Its deny canary (`:110-122`) checks absence
   from the evidence list rather than absence from the actual rewriter. Preserve
   useful artifact/version checks, but name them accurately and test instantiated
   rewrite admission separately. The runtime registry dependency is intentional;
   moving it out of production is an architectural tradeoff, not a necessary fix.

5. **Organize tests around behavior and execution paths.**
   Phase/batch names (`P1a`, `P5`, `B2`) make test discovery harder than capability
   names. Separate unit, live, slow-cancellation, and multi-FE tasks. Required CI
   lanes should fail clearly when their environment is absent. Add cross-feature
   cases: outer join + NULL-sensitive aggregate, cache + session override, HA
   proxy + failed helper, and constant-folded versus stored-column expressions.
   Fix `TestDorisIpPushdown.kt:41-43,102-108`: its “no pushdown” session still allows
   TopN, and LIMIT 10 exceeds the six-row fixture. Use genuinely disabled pushdown,
   selective limits, and ordered expected results.

6. **Trim build-policy boilerplate.** `config/detekt/detekt.yml` is an 862-line
   largely generated configuration despite `buildUponDefaultConfig=true`. Prefer
   documented project overrides, after checking equivalence against the pinned
   detekt version. Comments about other projects' old baselines obscure this
   project's actual policy.

## Reliability and coverage improvements

- Schedule slow cancellation and multi-FE checks explicitly; neither CI nor release
  currently starts the overlay, and the slow tag is excluded by default
  (`build.gradle.kts:103-113`). Add a real packaged-plugin load/catalog/query smoke
  to the release gate; filename/ServiceLoader checks and in-process installation
  do not exercise isolated plugin classloading.
- Expose cancellation queue rejection, expiry, and helper failure metrics. Current
  counters are internal/static and most failures are visible only at DEBUG.
- Measure metadata/statistics round trips on large catalogs before optimizing.
  The reader's max-of-counts heuristic cannot establish freshness after deletes or
  mixed-age analysis. Reject malformed numeric statistics per field/column rather
  than losing all healthy estimates through the outer exception handler.
- Clarify parser contracts before expanding them. `DorisColumnType` ignores trailing
  scalar text/empty argument tokens; the “strict” array float parser accepts Java
  numeric forms outside its stated grammar. These are robustness concerns, not
  demonstrated corruption of valid Doris output.
- Ordinary connection setup and helper DNS/authentication setup do not have an
  absolute wall-clock deadline. Preserve that distinction in operational claims;
  post-connect watchdogs do not bound pre-connect work.

## What is worth preserving

- Base JDBC integration and streaming scans are appropriate for Doris-owned execution.
- Authoritative `COLUMN_TYPE` metadata avoids real JDBC metadata corruption.
- Exact decimal/LARGEINT handling and refusal to guess ambiguous string-array text
  are sound choices.
- Layered read-only enforcement, parameterized values, quoted identifiers, and
  statement-specific cancellation IDs are substantive protections.
- The live differential, audit-SQL, and inverted-index tests are useful. Their
  remaining gaps call for more precise oracles, not abandoning live testing.

No SQL-user-reachable write escape was confirmed in this review.

## Verification

Fresh targeted probes used a UUID-named disposable schema, removed in `finally`,
and actual connector queries compared with `allow_pushdown_into_connectors=false`.
They reproduced R-04, R-05, R-07, R-08, R-16, and the new R-18 on the pinned release.
The R-05 probe used two URL entries for the same FE: it proves HA-proxy locking,
not cross-FE kill routing. R-08's comparison session disabled statistics specifically.

An attempted invalid-UTF-8 reproduction using `CAST(X'80' AS VARCHAR)` was rejected
by Trino on both paths. It does not establish reachability of the earlier E-12
hypothesis and is not counted as a finding.

Scratch reproducer and logs:

- `/tmp/opencode/DorisReview20261109.java`
- `/tmp/opencode/doris-review-20261109.init.gradle`
- `/tmp/opencode/doris-review-20261109-probe-focused.log`
- `/tmp/opencode/DorisReviewOracle.java` (`reviewOracleProbe` task in the same init script)
- `/tmp/opencode/doris-review-20261109-build.log`
- `/tmp/opencode/doris-review-20261109-smoke.log`

Packaged-plugin smoke passed in real Trino 483: ServiceLoader registered the
connector, a dynamic catalog was created, `count(*)` over `p0_probe.nums` returned
1,001,000, and the catalog was dropped. The manual Trino container was stopped
again. This is fresh local verification, not an existing CI gate. The three-FE
overlay was not started.

Full validation completed successfully:

```bash
mise exec -- ./gradlew build -PwithCancellation --rerun-tasks
```

- 346 tests reported: **341 passed, 5 skipped, zero failures/errors** across 45 suites.
  All five skips belong to the optional multi-FE class, including its inherited
  naming-convention check. The slow cancellation suite was included.
- Compilation, detekt, assembly, and assembly verification passed; 79 plugin jars.
- Build duration: 14m52s. Deprecated legacy-join API warnings remain upgrade debt.
- The previously intermittent trace-ID pin passed in this run; this does not
  establish that its historical flakiness is resolved.
- Targeted review probes and the packaged smoke ran alongside portions of the
  suite, using separate review schemas/catalogs.

The green existing suite does not cover the newly reproduced wrong-answer cases.
JUnit XML is under `build/test-results/test/`. The FE/BE containers were stopped
again after validation, preserving volumes; all three review-started services
are back in their original stopped state. Only this review document was added to
the checkout; production source and checked-in tests were not changed.

## Recommended order

1. Fix R-18's default-mode wrong answers, then R-04's outer-join composition.
2. Fix R-05 and the related cancellation worker/marker problems R-09/R-10.
3. Fix R-07/R-08, normalize configuration, and correct the `avg` recommendation.
4. Make assembly/release/smoke output selection deterministic and development
   targets explicit; add the missing CI lanes and repair misleading test oracles.
5. Simplify comments, repeated checks, and small APIs as the affected code is touched.

The September review already recorded most outstanding defects. The process
improvement is to turn prioritized findings into regression-backed fixes before
expanding functionality; another growing evidence ledger alone will not do that.
