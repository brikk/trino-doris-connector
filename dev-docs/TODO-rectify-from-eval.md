# TODO — rectify findings from the 2026-09-02 correctness/quality evaluation

Source: a full read of `src/` + `test/src/` + build/CI, two independent deep-review passes
(pushdown rules; cancellation/statistics/tests/CI), and a live verification run against the
compose cluster (`./gradlew build`: detekt clean, 259 tests / 30 suites green, 5 skipped =
multi-FE overlay, `verifyPluginAssembly OK`, 13m13s).

Each item has a stable label (`E-xx`). Address one at a time; mark `DONE <date>` inline and
cite the test/commit. Severity: **C**ritical / **H**igh / **M**edium / **L**ow / **N**it.

Overall verdict: the design is sound and unusually disciplined (typed fail-closed rules,
every literal bound as a parameter, reflective read-only guard, live-oracle tests). The
problems are (1) one live-confirmed correctness bug in the default string mode, (2) the
cancellation hook being attached to the wrong SPI seam, and (3) a body of confident KDoc/
registry prose that in several places contradicts the code it claims to prove.

---

## A. Correctness — confirmed or high-confidence

### E-01 [H] DONE 2026-09-04 — Decimal256-as-VARCHAR pushed value predicates; Doris compared numerically, Trino textually
- `DorisTypeMapping.kt:237-246` (`decimalMapping` → `unboundedVarcharMapping()` carries `VARCHAR_PUSHDOWN`).
- Reproduced on `p0_probe.scalars.c_dec76_10` (scratch test, since removed):
  - `WHERE c_dec76_10 > '5'` — Trino-local (NULL_ONLY) `[3]`; GUARDED/BINARY pushed `[1]`.
  - `WHERE c_dec76_10 = '123456789012345678901234567890.123456789'` — local `[]`; GUARDED `[1]`.
- The column's Trino type is a *rendering*, not a comparable value (same reasoning the code
  already applies to CONVERT_TO_VARCHAR complex types at :226-229). Doris implicitly casts the
  string literal to DECIMAL and compares numerically.
- Resolution: `decimal256TextMapping()` now uses `DISABLE_PUSHDOWN`; the scalar leaf rejects
  Decimal256 specifically (the broader native-type validation remains E-02); TopN and aggregation
  grouping gates reject Decimal256 handles by authoritative JDBC type name.
- Proof: `TestDorisP1aSmoke.testDecimal256TextNeverUsesRemoteNumericSemantics` pins the divergent
  range/equality answers under GUARDED and BINARY, retained Filter/TopN/Aggregation nodes, and
  lexicographic TopN output. Focused suite passed; full `./gradlew build` passed (detekt, live tests,
  plugin assembly) on 2026-09-04.

### E-02 [H] DONE 2026-09-04 - Scalar-tier leaf bypassed text-mapping pushdown restrictions
- `DorisScalarPushdownRules.kt:107-115`. Accepts any `Variable` whose Trino type is a value type
  and asserts (comment :112-113) it is a native column. False for Decimal256 (E-01) and for
  `unsupported-type-handling=CONVERT_TO_VARCHAR` ARRAY/MAP/STRUCT columns (`DorisTypeMapping.kt:173-174`).
- Original exposure: scalar expressions on textualized columns could operate on the native
  Doris type instead of the exposed VARCHAR. E-01 already blocked the Decimal256 case.
- Reproduced before this fix: `length(a_varchar50)` under CONVERT_TO_VARCHAR became remote
  `char_length(a_varchar50)` with no local ProjectNode. That fixture returned matching values,
  but the plan violated the mapping's disabled-pushdown contract.
- Sibling rules do this right (`RewriteStartsWith:375-378`, `RewriteStringLike:106-110`,
  `RewriteJsonExtractScalarEquality:106-107` check `DorisColumnType.parse(jdbcTypeName).baseName`).
- Resolution: VARCHAR leaves require native `varchar|string|text`; every accepted leaf's
  expression type and column type must match the existing Doris mapper's type for its JDBC
  type name. This reuses the authoritative precision/scale/boolean rules rather than duplicating
  them. Mapper-compatible synthetic projection handles remain eligible.
- Unit proof: `TestDorisScalarVariable` covers native and synthetic types, textualized complex
  types and Decimal256, missing/unknown metadata, and mismatched types/precision/scale.
- Live proof: `TestDorisB1ScalarPushdown.testScalarOperationsOnTextMappingsStayLocal` checks
  ARRAY/MAP/STRUCT and Decimal256 projections, nested functions, comparisons, and NULL predicates
  across all four string modes against a scalar-pushdown-disabled session, with retained plan nodes.
  `testScalarOperationsOnSyntheticGroupingKeysStillPush` verifies native derived-key composition.
- Verification: focused scalar/unit/smoke suites and detekt passed. Full build ran 265 tests:
  259 passed, 5 skipped, 1 failed in the unrelated direct-JDBC cancellation probe (see E-29).
  The cancellation suite passed on isolated rerun; plugin assembly and verification passed.

### E-03 [H] Cluster-scoped cancel never fires during the execute phase (no first row yet)
- `DorisClient.kt:465-476` hooks `abortReadConnection`, but `JdbcPageSource.close()` (483) only
  calls it when `resultSet != null`, i.e. after `executeQuery` has returned. A cancel while Doris
  is still computing (heavy pushed aggregation/join/TopN — the cases where cancel matters most)
  never reaches `clusterScopedCancel.onAbort`; `registrations` keeps the entry until GC.
- `TestDorisCancellation` uses a send-blocked cross-join (streaming phase) so this gap is untested.
- Fix: wrap the `PreparedStatement` from `getPreparedStatement` (`DorisClient.kt:455-463`) in a
  forwarding statement whose `cancel()` dispatches `clusterScopedCancel.onAbort(connection)`
  (async, non-blocking); register at `buildSql`/`getPreparedStatement` time so both phases are covered.

### E-04 [H] Connector/J's LB-unsafe `Statement.cancel()` is still invoked
- `JdbcPageSource.close()` calls `statement.cancel()` unconditionally before `abortReadConnection`.
  Connector/J opens a new session to the same `HostInfo` and issues `KILL QUERY <connection-id>`
  — behind an LB that is a kill of an unrelated same-user session on an arbitrary FE (the class
  header of `DorisClusterScopedCancel.kt:31-36` describes this hazard but nothing suppresses it).
- Fix: same forwarding statement as E-03; its `cancel()` must NOT delegate to Connector/J.

### E-05 [H] Query-scoped kill can fail a healthy query on legitimate early page-source close
- `DorisClusterScopedCancel.kt:228-237` `isRunningCancelCandidate` matches *every* running
  statement carrying the query-id marker. `abortReadConnection` also fires on non-error early
  close (`!resultSet.isAfterLast` after a non-pushed `LIMIT`/`EXISTS`/semi-join is satisfied).
  Closing scan A early then kills scan B of the same Trino query → "cancel query by user" → whole
  query fails. Stock `connection.abort()` was per-connection and had no such hazard.
- Fix: scope the kill to the aborting connection (per-split token in the comment, e.g.
  `/*trino_query_id=<id>;split=<n>*/`), or only issue query-wide kills when the Trino query
  itself is failing/cancelled (which requires the E-03 hook relocation).

### E-06 [M] DATE/TIMESTAMP literals outside Doris's representable range are pushed unguarded
- `DorisScalarPushdownRules.kt:122-137` (`RewriteScalarConstant`), also domain pushdown via
  `dorisDateColumnMapping` (no controller). Trino DATE spans ±5.8M years; Doris `0000-01-01..9999-12-31`.
  Doris non-strict mode yields NULL for invalid datetimes → `coalesce(d, DATE '2000-01-01') <
  DATE '10000-01-01'` drops every row remotely; Trino keeps all rows.
- Fix: reject out-of-range DATE/TIMESTAMP constants in `RewriteScalarConstant` and add a
  `PredicatePushdownController` to the DATE/DATETIME mappings that keeps out-of-range domain
  bounds local. Or: a live probe proving Doris errors loudly (then pin it).

### E-07 [M] `SET fetch_all_fe_for_system_table = true` failure is silently swallowed
- `DorisClusterScopedCancel.kt:127` `runCatching` around the SET. If the variable name is wrong
  for a Doris version (NOTES say it was renamed from `show_all_fe_connection`), the cross-FE
  mechanism silently depends on the undocumented default.
- Fix: log WARN (rate-limited/once) on failure; add a live pin asserting the variable exists.

### E-08 [M] Statistics: `ndv == 0` with non-null rows reported as `distinctValuesCount = 0`
- `DorisTableStatisticsReader.kt:149-153`. A stale sketch producing 0 tells the CBO the column has
  zero distinct values → near-zero cardinality after any equality filter.
- Fix: treat `ndv <= 0 && nonNullRows > 0` as unknown.

### E-09 [M] Statistics: index rows disambiguated by order, not `index_name`
- `DorisTableStatisticsReader.kt:120-122` assumes the base index row appears first (`putIfAbsent`).
- Fix: filter on the `index_name` column `== remoteTable`.

### E-10 [M] `DorisColumnType` parse failure is a hard `GENERIC_INTERNAL_ERROR` for the whole table
- `DorisColumnType.kt:65-66`; called per column from `readColumns` (`DorisClient.kt:376-402`).
  One `varchar(99999999999)` (Int overflow) or an unforeseen COLUMN_TYPE shape makes the table
  — and `information_schema.columns` for the catalog — unreadable.
- Fix: unparseable columns follow the existing "hidden" policy (map to `Optional.empty()` with a
  WARN); reserve throws for internal callers.

### E-11 [L] FLOAT read path uses `getFloat`; DOUBLE path deliberately avoids `getDouble` for Infinity
- `DorisTypeMapping.kt:428-435` vs `:444-465`. Connector/J's `FloatValueFactory` has the same
  range check that makes `getDouble` throw on `Infinity`. The P0 probe only proved `NaN` and
  `±3.402823E38` for FLOAT, not `Infinity`.
- Fix: live-probe `cast('Infinity' AS FLOAT)` through the connector; if it throws, switch FLOAT
  to the `getString` + `toFloat()` pattern (and cover in `TestDorisP1aSmoke`).

### E-12 [L] Slice → String → Slice round trip in LIKE/starts_with corrupts invalid UTF-8
- `RewriteStringLike.kt:68,76`, `DorisScalarPushdownRules.kt:381,385`. Non-UTF-8 bytes become
  U+FFFD before binding, so the pushed pattern differs from Trino's. (3-arg LIKE path :93 is fine.)
- Fix: do the escaping at the byte level or reject non-UTF-8 slices (stay local).

### E-13 [L] `isRunningCancelCandidate` excludes rows by substring
- `DorisClusterScopedCancel.kt:233`: any statement text containing `KILL QUERY` / `SHOW FULL
  PROCESSLIST` is never a candidate — a user literal `WHERE msg = 'kill query'` makes that query
  un-cancellable by this path.
- Fix: exclude by the helper's own connection id, or require the statement to *start with* SHOW/KILL.

### E-14 [L] Cancel helper: no statement timeout, unbounded cached thread pool, interrupt swallowed
- `DorisClusterScopedCancel.kt:139-161` bounds the loop (15s) but each `SHOW FULL PROCESSLIST`/
  `KILL QUERY` has no `setQueryTimeout`; `Executors.newCachedThreadPool` (:207-209) spawns a thread
  per concurrent cancel; `InterruptedException` from `Thread.sleep` (:159) is caught generically
  (:131) without restoring the flag; `killsDispatched` (:99) increments before `execute` can reject (:103).
- Fix: bounded pool + bounded queue with drop-on-reject; `setQueryTimeout` on helper statements;
  restore interrupt; count after successful dispatch.

### E-15 [L] `registrations` entries only removed on abort
- `DorisClient.kt:466`: normal completion (`isAfterLast`) never unregisters; `WeakHashMap` makes it
  GC-bounded, but each entry holds a strong `ConnectorSession`.
- Fix: `clusterScopedCancel.unregister(connection)` in the `isAfterLast` branch.

---

## B. Comment / registry / config prose that contradicts the code

These matter more than usual here because the project's safety argument leans on "the KDoc and
the evidence registry are the proof". Fix the text or the code — whichever is actually intended.

### E-16 [M] `DorisPushdownEvidence` "structural" self-checks are decorative
- `DorisPushdownEvidence.kt:337-341, 353-372`. Only the external-registry tuple equality is
  enforced at startup. `dorisRendering.contains(expectedTarget)` (:369) is a substring test on a
  doc string that passes via trailing comments for `STARTS_WITH` (:329), `DATE_OF_DATETIME` (:200),
  `LENGTH` (:311). `Treatment`/`guard`/`liveProof` are never read outside the test. No rule
  references its `Evidence` at runtime.
- The comment at :337 ("one Evidence entry per pushdown rule, no more, no fewer") is false: no
  entries for `RewriteStringLike`, the scalar bridges, `RewriteValueSafeNot/Logical`,
  `RewriteScalarProjection/Variable/Constant`, or any of the six aggregate rules.
- Fix: either have each rule expose its rendering template / target name and assert it in a unit
  test that instantiates the rule, or reword the KDoc to "documentation + artifact-drift canary"
  and drop `Treatment`/`guard`.

### E-17 [M] `DorisConfig` says GUARDED keeps a retained Trino filter; it doesn't
- `DorisConfig.kt:68`: `GUARDED (superset pre-filter, Trino filter retained; default)`.
  `guardedResult` (`DorisTypeMapping.kt:365-375`) and `DorisStringPushdownMode.kt:29-31` push
  **fully with no retained filter**. Operators reading the config description assume a safety net
  that does not exist.
- Fix: correct the `@ConfigDescription`. Also `DorisSessionProperties.kt` `query_timeout`
  description says "enforced via JDBC (KILL QUERY on expiry)" — implementation is server-side
  `SET query_timeout` (`DorisClient.kt:445-448`).

### E-18 [M] NULL_ONLY string mode gate in the scalar tier is shallow
- `DorisScalarPushdownRules.kt:287-292` checks only top-level operand types; :325-348 (IS NULL
  bridge) and :412-436 (projection) have no mode check. Under NULL_ONLY these still push:
  `nullif(varchar_col, 'x') IS NULL` (≡ `v = 'x' OR v IS NULL`), `length(varchar_col) = 3`,
  `GROUP BY lower(varchar_col)`. Contradicts `DorisStringPushdownMode.kt:25` ("null-ness only").
- Fix: walk the operand tree, or have `RewriteScalarVariable`/`Constant` reject VARCHAR under
  NULL_ONLY unless the root is `$is_null(Variable)`; gate `RewriteScalarProjection` for VARCHAR results.

### E-19 [M] LOWER/UPPER evidence says "BINARY/FULL only"; the rule runs in every mode
- `DorisPushdownEvidence.kt:285` vs `DorisScalarPushdownRules.kt:212-231` (`RewriteCaseFold` has
  no `isEnabled`). Pick one.

### E-20 [M] `starts_with`: KDoc says "Doris has no starts_with"; registry maps `starts_with → starts_with`; policy differs from LIKE
- `DorisScalarPushdownRules.kt:352-353, 364-365`; `DorisPushdownEvidence.kt:319-332`. Doris does
  document `STARTS_WITH`. Also `starts_with(v,'a')` → `v LIKE 'a%'` pushes in GUARDED while a
  user-written `v LIKE 'a%'` is BINARY/FULL-only (`RewriteStringLike.kt:57-58`) — same SQL, two trust levels.
- Fix: correct the KDoc/hazard text; apply one mode threshold to both renderings.

### E-21 [L] Stale IP-array comments
- `DorisArrayPushdownRules.kt:79-80` ("IPADDRESS excluded until live-proven") and `:422-423`
  ("FLOAT/DOUBLE/IP element arrays are excluded") vs `isPushableElement` at `:206` which includes
  `IpAddressElement`. Code appears intended; comments are wrong.

### E-22 [L] `isExactPushableKeyType` KDoc claims min/max keys == TopN sort keys
- `DorisAggregatePushdownRules.kt:59-61` vs `DorisClient.kt:508-509` (VARCHAR TopN keys in
  BINARY/FULL; `isExactPushableKeyType` never admits VARCHAR). Document the divergence or align.

### E-23 [L] "plain column-vs-literal comparisons never arrive here" is inaccurate
- `DorisScalarPushdownRules.kt:266-267`. They can (controller declined the domain; column-vs-column).
  Code at :281-283 handles it correctly; fix the comment.

---

## C. Code quality

### E-24 [L] Duplication
- `COMPARISON_OPERATORS` + `flip` copied verbatim: `DorisArrayPushdownRules.kt:388-404` and
  `:474-489`; third operator map at `DorisScalarPushdownRules.kt:308-315`.
- `rejected()` + `REJECTION_COUNTS`: `DorisAggregatePushdownRules.kt:53-88` and
  `DorisArrayPushdownRules.kt:183-194`. Log format `"…; total for category=%s"` prints the
  *count* labelled as "category" in both.
- `DorisScalarRewriterHolder` (`DorisScalarPushdownRules.kt:258-263`) ≡ `DorisValueSafeRewriter`
  (`DorisValueSafeComposition.kt:57-62`).
- String-column base-name allowlist `varchar/string/text` in `RewriteStartsWith:376` and `RewriteStringLike:108`.
- Max datetime precision: `MAX_DATETIME_PRECISION`, `DORIS_MAX_DATETIME_PRECISION`
  (`DorisTypeMapping.kt:308-309`), `RewriteDateTrunc.DORIS_MAX_DATETIME_PRECISION`
  (`RewriteDateProjections.kt:134`), bare `6` at `:146`.
- NUL-byte scan implemented three ways; `domainValuesContainNulByte` and `collectDomainSlices`
  (`DorisTypeMapping.kt:378-391, 545-558`) duplicate the same domain walk.

### E-25 [L] Minor type/API smells
- `DorisArrayPushdownRules.kt:109,183` — `literalNeedle` returns `Optional<Any>?` (nullable
  Optional never empty); `rejected()` typed `PushableArrayColumn?` but used for side effects.
- `DorisAggregatePushdownRules.kt:319,362,408` — `MinMaxBy`/`AnyValue`/`ApproxDistinct` hardcode
  `listOf()` for parameters, discarding `rewriteExpression(...).parameters()` (inconsistent with
  `MinMax:126`/`Sum:193`).
- `DorisAggregatePushdownRules.kt:114,174,238,293-294,352,396` — unchecked `as JdbcColumnHandle`
  + `verify(...)` turn unexpected shapes into planning-time exceptions rather than fail-closed `Optional.empty()`.
- `DorisScalarPushdownRules.kt:413` — `RewriteScalarProjection(private val scalar: …)` param unused.
- `DorisArrayPushdownRules.kt:491-494` — `RewriteCardinalityComparison` pattern lacks
  `argumentCount()` while reading `arguments[2]` at :436.
- `RewriteDateProjections.kt:127` — `date_trunc` unit match is case-sensitive; Trino lowercases.
- `DorisQueryBuilder.kt:37` — public `class` while every other Doris class is `internal`.
- `DorisJdbcConfig.kt` `isUrlWithoutDatabase` — `parseConnectionString(null)` may NPE before the
  `@NotNull` violation on `connectionUrl` is reported (catches `CJException` only).
- Inconsistent FQN usage (`io.trino.spi.type.BigintType` inline at `DorisScalarPushdownRules.kt:78,90`,
  `io.trino.plugin.base.projection.…` at `DorisClient.kt:575-579`) alongside imports.
- `DorisColumnType.kt:47-56` silently ignores trailing text after `)`; `"decimal(38,)"` → `[38]`,
  `"varchar()"` → `[]`, negative args accepted.

### E-26 [L] No session-level kill switch for the scalar / projection tiers
- LIKE, starts_with, approx_distinct have `isEnabled`; the scalar tier and `RewriteScalarProjection`
  do not. If E-02/E-06 bite in production there is no per-query opt-out.

### E-27 [N] `TODO(adbc):` bypasses detekt's `ForbiddenComment` (`TODO:`)
- `DorisClientModule.kt:107`. Either allow the pattern explicitly in `detekt.yml` or reword.

### E-28 [N] Runtime dependency exists solely for a startup self-check
- `brikk-sql-metadata-jvm` + `kotlinx-serialization-*` are bundled into the plugin dir so that
  `DorisClientModule.setup` can call `DorisPushdownEvidence.verifyAgainstRegistry()` on every
  connector construction. Consider making this a test-scope drift check (it already is, in
  `TestDorisPushdownEvidenceDrift`) and dropping the production dependency, or document why a
  runtime failure mode is preferred.

---

## D. Tests / CI

### E-29 [M] The two suites that prove the headline cancellation fix never run in CI
- E-02 verification (2026-09-04) also exposed an intermittent failure in
  `TestDorisClusterScopedCancel.testKillByQueryIdReleasesAndRepeatIsHarmless`: the processlist
  supplied a QueryId rejected by `isValidQueryId` at line 142. The suite passed on isolated rerun.
  Investigate transient processlist IDs and improve assertion diagnostics when addressing cancellation.
- `TestDorisCancellation` is `@Tag("cancellation")` and excluded by default (`build.gradle.kts:110-114`);
  `TestDorisMultiFeFailover` is `@EnabledIf overlayIsUp` and the 3-FE overlay is never started in CI.
- `TestDorisClusterScopedCancel` live tests (119-213) re-implement the scan→kill→verify loop in
  test code and never call `killQuietly`/`killUntilReleased`. Net: the production kill loop has
  no CI coverage.
- Fix: add a hermetic unit test of `killUntilReleased` against a fake `Connection`/`Statement`;
  run `-PwithCancellation` in CI (or nightly); consider a nightly multi-FE job.

### E-30 [M] Live suites hard-fail (not skip) with a hard-coded cluster
- `DorisQueryRunner.kt:32-33` pins `127.0.0.1:9130` root/no-password; `DorisTestCluster.kt:26,95-118`
  pins `docker exec trino-doris-fe`; zero `assumeTrue`/`Assumptions`. ~25 of 31 classes need the cluster.
- Fix: env-var override for URL/user/container name; `assumeTrue(clusterReachable)` or a clear
  single-line failure explaining `./compose/up.sh`.

### E-31 [L] `DorisFixtures` is destructive on fingerprint mismatch
- `DorisFixtures.kt:105` `DROP DATABASE p0_probe` on whatever cluster answers at 9130. Fine for CI;
  guard with an env opt-in or a cluster-identity check for dev hosts.

### E-32 [L] CI hygiene
- `.github/workflows/ci.yml`: actions tag-pinned (`@v4`, `jlumbroso/free-disk-space@v1.3.1`) not
  SHA-pinned; no top-level `permissions: contents: read`.
- `.github/workflows/release.yml`: comment at 130-131 says "auto-generate notes" but no
  `--generate-notes` is passed (135-142); any push to `release-*` cuts a release with no check that
  the branch version matches `gradle.properties`; checksums are produced in the same job (no
  independent integrity); no smoke test that the packaged plugin dir loads in a real Trino server.

### E-33 [N] `TestDorisTypeMapping.testParse` gaps
- Not covered: single-arg decimal, empty/negative args, trailing text (see E-25 last bullet).

---

## Suggested order

1. E-01, E-02 (default-mode correctness; small, testable) →
2. E-03, E-04, E-05 (cancellation seam; one forwarding-statement change resolves all three) →
3. E-17, E-16, E-18, E-19, E-20 (make prose match code; decide NULL_ONLY/GUARDED contracts) →
4. E-06, E-07, E-08, E-09, E-10, E-11 →
5. E-29, E-30 (CI coverage of the cancel path; portable live tests) →
6. Everything else opportunistically.
