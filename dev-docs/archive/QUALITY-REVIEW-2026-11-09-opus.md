# Code Quality Review: 2026-11-09

Reviewed baseline: `9871b52` (`build: target Doris 4.1.4.1 release images`), on `main`.

Scope: all production source (`src/`, 28 files, about 5,000 lines), a sample of the test
suites, the build, CI and compose setup, checked against
[REVIEW-2026-09-05.md](REVIEW-2026-09-05.md). `compileKotlin`, `compileTestKotlin` and
`detekt` pass. The live test suite was not run against Doris for this review.

Item IDs (`QR-xx`) are stable; refer to them as "do QR-05". Items carried over from the
September review keep their original `R-xx` IDs.

## Bottom Line

The design is good: read-only enforcement in layers, careful type mapping, and pushdown
that is only enabled when it has been checked. You don't need a rewrite. The weaknesses are
of two kinds:

1. **Five wrong-answer, hang or safety bugs from September are still open**, along with
   several smaller ones.
2. **The code has gotten hard to read.** The cause is mostly the comments and copy-pasted
   checks, not the logic. Some of the copies have already started to disagree with each
   other, which is the same class of problem the September bugs came from.

## 1. Still Open From The September Review

I checked each of these against the current code. None has changed.

| ID | Issue | Where | Severity |
|---|---|---|---|
| R-04 | min_by/max_by NOT NULL check is wrong after an outer join (live wrong answer) | `DorisAggregatePushdownRules.kt:308` | P1 |
| R-05 | Cancel can block on Connector/J's HA proxy lock; `close()` then waits without being interruptible | `DorisPreparedStatement.kt:80,100` | P1 |
| R-06 | Dev Doris/Trino ports bound on all interfaces, root with no password | `compose/*.yml` | P1 (dev) |
| R-07 | Non-ASCII LIKE `ESCAPE` turns a valid query into a Doris error | `RewriteStringLike.kt` (3-argument path) | P2 |
| R-08 | `statistics_enabled=false` is bypassed by the stats cache | `DorisClient.kt:334` | P2 |
| R-11 | No range checks on timeouts; values under 1s become 0, which means unlimited | `DorisConfig.kt`, `DorisClientModule.kt:166` | P2 |
| R-12 | `pluginAssemble` uses `Copy`, so old jars are left behind | `build.gradle.kts:137` | P2 |
| R-13 | Any release branch suffix becomes the published ABI version | `.github/workflows/release.yml:48-60` | P2 |
| R-16 | README recommends `sum/count` as a replacement for `avg` (gives 1 instead of 1.5) | `README.md:81,137` | P2 |

R-04 and R-05 matter most. R-16 is a one-line README fix and should go in today.

## 2. Comments Are The Biggest Readability Problem

### QR-01: Comment Style

About 1,240 lines of KDoc and comments sit next to roughly 2,600 lines of actual code.
That ratio isn't automatically bad, but these comments read like a lab notebook:

- **Internal codes that nobody outside the project can follow.** There are 176 references
  like `PLAN G7.5`, `SR K11/R2`, `ledger §D`, `PROBE Impl #7`, `spike F4` and
  `READ-ONLY-MAX batch 3`. A new reader has to open six documents to follow one function.
- **History mixed in with the current rules.** Comments explain what was true in P1a, P2
  and so on, instead of what is true now.
- **Shouting** (`NEVER`, `ONLY`, `EXACTLY`, `LIVE-PROVEN`) and lists of probe results
  inside KDoc.

**The rule to adopt:** state what is true now in one to three plain sentences, then link
one evidence document. Probe history belongs in `dev-docs/` and git.

For example, the 15-line KDoc on `supportsAggregationPushdown` (`DorisClient.kt:622-636`)
could become:

```kotlin
/**
 * Grouping keys must be equal in Doris exactly when they are equal in Trino: exact numeric,
 * temporal and boolean types, plus native VARCHAR/STRING (byte-equal on both engines).
 * CHAR (trailing spaces), REAL/DOUBLE (NaN, -0.0) and text-rendered complex types stay local.
 * Evidence: dev-docs/REPORT-string-comparison-probe-4.1.3.md
 */
```

### QR-02: Comments That Are Now Wrong

This is the real risk, because readers trust them.

| Location | Comment says | Code does |
|---|---|---|
| `DorisTypeMapping.kt:91`, `:184`, `DorisClient.kt:403` | ARRAY isn't exposed and has no pushdown "until P2" | Both are implemented |
| `DorisClient.kt:429-431` | Read-only enforcement "lands in P1b" | It shipped long ago |
| `DorisArrayPushdownRules.kt:57-60` | "No boolean-composition rules exist by design" | The value-safe NOT/AND/OR tier exists |
| `DorisArrayPushdownRules.kt:79-80` | IPADDRESS elements are excluded | `isPushableElement` includes them |
| `DorisAggregatePushdownRules.kt:57-62` | VARCHAR is never a GROUP BY key | `DorisClient.supportsAggregationPushdown` allows native VARCHAR |
| `DorisCancelDriver.kt:32-33` | Load-balance, replication and SRV URLs are rejected | They're handled (see `resolvedHosts`) |
| `DorisPushdownEvidence.kt:288` | lower/upper only push in BINARY/FULL | They push in every mode |
| `DorisScalarPushdownRules.kt:364`, `DorisPushdownEvidence.kt:322` | "Doris has no starts_with" | Doris has one |
| `DorisPlugin.kt`, `DorisQueryRunner.kt` | Baseline is Doris 4.1.3; tests live at `jvm/trino-doris/compose` | The project is on 4.1.4.1, and that path is from the old monorepo |
| `DorisArrayElement.kt:37` | Points to `AGENTS.md` | That file isn't in this repo |
| `DorisClient.kt:120-141` | Two KDoc blocks stacked on one property | The array-tier doc is attached to the scalar rewriter |

## 3. Duplicated Rules That Have Started To Drift

### QR-03: One Native-String Check

The "is this a native Doris string column?" check exists in four hand-written copies:

- `DorisClient.kt:664`
- `DorisScalarPushdownRules.kt:115` and `:388`
- `RewriteStringLike.kt:108`

It is **missing** from three aggregate rules that accept any `VarcharType`:
`DorisAggregatePushdownRules.kt:329`, `:355` and `:401` (min_by/max_by values, any_value
and approx_distinct). A Decimal256, or a MAP/ARRAY shown as text, can reach them. This
probably doesn't give wrong answers today, because those rules just pass the value through.
But at best Doris rejects the query when it could have run locally, and it is exactly how
R-03 happened.

Fix: put the check in one helper, `DorisColumnType.isNativeString(handle)`, and use it
everywhere.

### QR-04: Other Copies To Merge

Each of these should become one shared definition:

- **Comparison operator maps and `flip()`:** three copies of the map and two of `flip()`
  (`DorisArrayPushdownRules.kt:396`, `:406`, `:482`, `:491`;
  `DorisScalarPushdownRules.kt:320`).
- **Rewriter holders:** `DorisValueSafeRewriter` and `DorisScalarRewriterHolder` are the
  same class.
- **Domain scans:** `domainValuesContainNulByte` re-implements `collectDomainSlices`, and
  `sliceContainsNulByte` is a third NUL scan (`DorisTypeMapping.kt:412`, `:595`;
  `DorisScalarPushdownRules.kt:152`).
- **Constants:** max datetime precision 6 is defined three times plus a bare `6`
  (`RewriteDateProjections.kt:132`, `:144`). 38 appears as three constants and 16 as two.
- **Small helpers:**
  - `unboundedVarcharTextMapping` and `decimal256TextMapping` are identical.
  - The decimal argument check is copied (`DorisTypeMapping.kt:211`, `:239`).
  - The remote-schema lookup is copied (`DorisClient.kt:339`, `:361`).

## 4. Structure

### QR-05: Split `DorisClient.kt` (704 Lines)

The rewriter setup is scattered between methods (lines 142, 155, 248, 555 and 578). Move
all of it into a small `DorisRewriters` factory so readers have one place to see what can
be pushed down. There are also two functions named `isPushableSortKey` with different
parameters (`:510` and `:692`), which is confusing.

### QR-06: Split `DorisTypeMapping.kt` (740 Lines)

Its companion object mixes type mapping, string/temporal/IP pushdown controllers, IP
encoding and LARGEINT parsing. Splitting it into `DorisTypeMapping`,
`DorisPushdownControllers` and `DorisIpAddress` would make each part readable on its own.

### QR-07: Move The Evidence Check Out Of Startup

`DorisPushdownEvidence.verifyAgainstRegistry()` runs on every catalog creation, comparing a
pinned jar against constants. Since the result is fixed at build time, a unit test does the
same job. That would remove a production dependency (plus two kotlinx jars) and a way for
a catalog to fail to start because a documentation string changed. The September review
(E-28) called this a deliberate choice; it is worth revisiting.

## 5. Smaller Kotlin Hygiene

- **QR-08: Inline fully-qualified names.** There are 31, even where the import exists, e.g.
  `com.google.common.collect.ImmutableSet` at `DorisClient.kt:582` and
  `io.trino.spi.type.VarcharType` in the aggregate rules.
- **QR-09: Unused constructor parameter.** `RewriteScalarProjection(scalar)` never uses
  `scalar` (`DorisScalarPushdownRules.kt:424`).
- **QR-10: Aggregate rules fail instead of falling back.** They use `verify(...)` and
  unchecked `as JdbcColumnHandle`, so an unexpected shape fails the whole query instead of
  running locally. They should return `Optional.empty()`.
- **QR-11: `runCatching` catches `Throwable`**, including `Error`s
  (`DorisTableStatisticsReader.kt:115`, `:196`). Catch `SQLException` and
  `NumberFormatException` instead.
- **QR-12: Mixed return shapes** like `Optional<Any>?` (`literalNeedle`) and `rejected()`
  helpers that return a fake value. A tiny `Optional.orNull()` extension would flatten the
  repeated `if (x.isEmpty) return Optional.empty(); x.get()` pattern.
- **QR-13: Counters that do little.** The rejection counters in the array and aggregate
  rules are global, never exposed, and keyed by message text. The cancel counters
  (`DorisClusterScopedCancel.kt:259-263`) are global mutable test hooks in production code.
  Remove them, or expose real metrics.
- **QR-14: The `doris.time-zone` check is in the wrong place.** It's a `require` inside the
  Guice module (`DorisClientModule.kt:169`). Move it to `DorisConfig` with `@AssertTrue`,
  next to the duration checks R-11 needs, so errors show up as normal config errors.

## 6. Tests

The tests are a strength: they compare results against a live server and assert on query
plans. For maintainability:

- **QR-15: Test classes are named by project phase** (`TestDorisP2bPushdown`,
  `TestDorisB3DistinctHaving`). Names by feature (`TestArrayPushdown`,
  `TestDistinctAndHaving`) would tell a reader where to look.
- **QR-16: Copy-pasted helpers.** `remoteSql` is defined 11 times, `provisionFixture` 16,
  `rows` 6 and `assertPushedAndIdentical` 5. Move them into an `AbstractDorisPushdownTest`
  base class.
- **QR-17: The "local" reference session differs between suites.** Some disable
  `complex_expression_pushdown`, some `aggregation_pushdown_enabled`, some
  `allow_pushdown_into_connectors`. The narrower ones still push part of the query, so they
  don't give a fully local answer to compare against. Use
  `allow_pushdown_into_connectors=false` everywhere unless a test needs something narrower.
- **QR-18: Tests depend on global counters.** `TestDorisScanCancellation` asserts changes
  in `killsDispatched`, which will break if tests ever run in parallel.

## Suggested Order

1. **Today:** fix the README `avg` advice (R-16) and bind the compose ports to `127.0.0.1`
   (R-06).
2. **Fix R-04 and R-05**, each with a regression test.
3. **Merge the duplicated checks (QR-03, QR-04).** That is a correctness fix disguised as
   cleanup. Then fix R-07, R-08 and R-11.
4. **Rewrite the comments file by file (QR-01, QR-02).** Remove the internal codes and
   history, and fix everything in the stale-comment table. Do it in small commits per file
   so reviewers can check that only comments changed.
5. **Split `DorisClient` and `DorisTypeMapping` (QR-05, QR-06)**, then move the evidence
   check into a test (QR-07) and rename the test suites (QR-15).
