# Golden baseline: Trino 484 / Doris 4.1.5

Date: 2026-10-31. Worktree/branch: `trino-484`.

**Historical pre-fix measurements.** The subsequent approved fixes and final
staged qualification are in the [release-safety report](REPORT-trino-484-release-safety.md).
The observations below are preserved, not current unresolved status claims.

**Pre-fix assessment: version lock established; release blocked.** The full default run has
351 passes, one Unicode case-fold failure, and five optional overlay skips.
Subsequent opt-in probes reproduce **both R-04 and R-05** on this exact stack.
Neither production issue is fixed by this work. Measurements are Linux/amd64;
multi-architecture image pinning is not an ARM runtime qualification claim.

## Locked inputs

- Trino **484**, MySQL Connector/J **26.7.0**, JDK **25**.
- Doris FE: `apache/doris:fe-4.1.5@sha256:bd0149d76fd02429ee4ab751f34c16948fbf761a886070489fdebf7b2069305c`.
- Doris BE: `apache/doris:be-4.1.5@sha256:10d282e1bd685f8c0d71b3b42bbd0841bbb20909de2cd847923f00378b6b9a11`.
- Both native engines report **`doris-4.1.5-release-917b20be838`**. The existing
  cluster ID `1945291672` and BE ID `1788328290997` were retained; no volumes
  were removed. The images match both single-FE and multi-FE Compose pins.
- Metadata coordinate: **`dev.brikk.house:brikk-sql-metadata-jvm:0.21.0`**.
  Published Gradle metadata names the resolved file
  **`brikk-sql-metadata-jvmMain-0.21.0.jar`**. Its exact binary SHA-256 is
  **`506ea02a67bbf983122a978fb3fb0657ff6d606a801820b0e247aa509f5678b2`**.
- Kotlin compiler/BOM **2.4.10**, aligned with the metadata artifact's declared
  runtime rather than enforcing the old 2.3.10 stdlib underneath it.

`DorisTestCluster.requireGoldenVersion()` checks native FE and BE versions
before base fixture use, and the release compatibility suite pins the same
identity. This is a validation/release baseline lock, not a new production
connector restriction on which Doris servers an operator may configure.

## Metadata integration

The generated released lookup retains `json_extract_scalar -> json_unquote`,
now CONDITIONALLY_EQUIVALENT with provenance
`trino-484-doris-4.1.5-json.md#path-semantics`. All 19 existing evidence tuples
were checked by the first focused run. The only failure in that run was the
old assumption about the resolved artifact's filename (36 tests, 35 passed,
one failed); the filename pin now matches the published Gradle variant name.

Conditional evidence now distinguishes a rendered wrapper from a closed
admission contract. JSON uses the latter, backed by executable tests for native
JSON-only input, equality-only/top-level scope, dotted paths, and safe literals.
Construction rejects missing/mismatched admission contracts or unguarded
conditional treatment. The unquote wrapper is not misrepresented as enough to
resolve numeric, non-scalar, or path hazards.

## Stored JSON and JDBC wire probes

Direct Connector/J 26.7.0 reads confirm:

- SQL NULL: `getString`/`getObject` return null, `wasNull=true`.
- JSON null: native reads return the text `null`, `wasNull=false`, while Doris
  `CAST(doc AS STRING)` returns SQL NULL. The native mapper must retain this
  distinction, not implement the wire read through that SQL cast.
- Stored `JSON_PARSE('{"n":1e9999}')` is returned as **`{"n":inf}`**; extraction
  returns `inf`. This is invalid Trino JSON, not an ordinary numeric spelling.
- Stored parsed `1.2300` is returned as `1.23`. Source scale lost in Doris cannot
  be recovered by the connector.

Non-finite-looking equality literals (`inf`, `Infinity`, `NaN`, optional sign,
case-insensitive) now stay local alongside ordinary numeric-looking literals.
The JSON decoder remains strict: invalid non-finite wire JSON is rejected,
never silently converted to text or SQL NULL. A predicate that filters out an
unreadable row need not decode it; the connector does not claim arbitrary
Doris non-finite JSON is representable as Trino JSON.

`TestDorisGoldenJsonWire` adds native-column, cast/null, strict-error, guarded
predicate, numeric normalization, and duplicate-member probes. Readable-row
differentials use a separate relation from the deliberately unreadable overflow
row so both the ordinary and all-pushdown-OFF oracles have valid JSON inputs.

## Validation artifacts

The clean full command was:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew clean build \
  --no-daemon --console=plain
```

Result: **357 tests, 351 passed, one failed, zero errors, five skipped**, across
46 suites, in 15m28s. The sole failure is
`TestDorisB1ScalarPushdown.testCaseFoldAgreementBatteryPins` (`ß` versus `SS`).
Compilation, detekt and 79-JAR assembly verification passed. Metadata/JAR SHA,
native golden versions, stored JSON, production cancellation, and the other
default live suites passed. The five skips are the optional multi-FE overlay;
the existing opt-in slow cancellation suite was not run. XML is preserved in
`full-build-xml/`, not replaced by a passing subset or by the diagnostic runs.

Focused lock-in qualification passed **17 tests, zero failures/errors/skips**,
plus detekt and 79-JAR assembly verification. This includes the released artifact
version/SHA, all registry tuples, the conditional admission policy, and native
stored-column wire behavior.

The first native-wire focused attempt had three failures because Trino's
`QueryAssertions` plan helpers also execute an all-pushdown-OFF correctness
oracle. That oracle decoded the intentionally invalid overflow row, even when
the pushed numeric id domain would exclude it. The unreadable value now lives
in a separate `nonfinite` relation, and its plan is inspected with EXPLAIN
instead of a helper that requires successful query execution. The failed XML
is preserved in `native-focused-xml/`; the passing rerun is in
`native-focused-rerun-xml/`. The strict decoder/error expectation was not relaxed.

The real Trino 484 server loaded the clean-built plugin via ServiceLoader with
metadata 0.21.0, Kotlin 2.4.10 and serialization 1.11.0. Catalog create/drop,
schema discovery, JSON/SQL-null distinction, scalar count=8, stored decimal
reading, and selective predicate/TopN smoke passed. The image was stopped after
the smoke (not the single-FE test cluster).

Logs and source/JAR identities: `/tmp/opencode/trino-484-415-lock/`.
Initial artifacts include `native-versions.log`, `json-native-probe.log`,
`json-jdbc-wire.log`, the exact downloaded metadata JAR/module/source archive,
and the failed first focused XML (`focused-xml/`).

## New 4.1.5 blocker: Unicode case folding

The full baseline run exposed the existing case-fold identity battery failing.
Independent native-engine probes and a **real assembled-plugin** table query
confirm that this is a semantic divergence, not just a stale expected string:

| Input/expression | Trino 484 local | Doris 4.1.5 / pushed connector |
|---|---|---|
| `upper('ß')` | `ß` | `SS` |
| `lower('ΑΣ')` | `ασ` (U+03C3) | `ας` (final sigma U+03C2) |
| `lower('İ')` | `i` | `i` + combining dot U+0307 |

The connector query used ordinary native VARCHAR columns under its default
string mode. EXPLAIN showed `upper(v)` and `lower(v)` in the remote query;
turning connector pushdown off returned Trino's correct local values. Thus this
is an additional **default-path wrong-answer release blocker**, independent of
the opt-in join issue R-04 and HA cancellation issue R-05. The July LOWER/UPPER
registry entries retained in metadata 0.21.0 do not clear this new evidence.

The failing identity assertion and production case-fold rules are left unchanged
for the requested report-only review. Do not update Trino expectations to Doris
results or treat the successful JSON/installation smoke as universal qualification.
Raw results and plan: `casefold-{doris,trino,connector}.log` in the artifact
directory above. These results make the full run red; no passing subset is
being substituted for that outcome.

## R-04 / R-05 follow-up

The version lock/full baseline run completed before these measurements. Run:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew \
  -I dev-docs/probes/review-probes.init.gradle test \
  --tests '*TestDorisR04Review' --tests '*TestDorisR05Review' \
  --no-daemon --console=plain
```

The final diagnostic run has **7 tests: 3 passed, 4 failed, zero errors/skips**.
The four failures are the assertions requiring correct LEFT/RIGHT aggregate
results and non-blocking HA cancellation/concurrent close. They are expected
to remain red until fixes land, not disabled release tests.

Opt-in diagnostics are in `dev-docs/probes/`, with an init script that adds
their sources only when explicitly requested. They are not silently included
in the normal build or release gate. Their assertions require correct results
and non-blocking behavior; an unfixed issue deliberately makes the diagnostic
run red. See that directory's README for reproduction and scope limits.

### R-04: reproduced wrong outer-join aggregates

Fixture: `l(id NOT NULL) = {1,2,3}`;
`r(id NOT NULL,v NOT NULL) = {(2,60)}`. Both unmatched rows have NULL payloads,
and one wins the minimum key while the other wins the maximum key.

| Join with null-supplying `r` | Aggregate | Local / default join OFF | EAGER join ON |
|---|---|---|---|
| LEFT | `min_by(r.v,l.id)` | SQL NULL | 60 |
| LEFT | `max_by(r.v,l.id)` | SQL NULL | 60 |
| RIGHT (tables reversed) | `min_by(r.v,l.id)` | SQL NULL | 60 |
| RIGHT (tables reversed) | `max_by(r.v,l.id)` | SQL NULL | 60 |

All four differences reproduced in both diagnostic runs. EXPLAIN shows the
aggregate **and** outer join inside one remote TableScan query. Base-table and
matched INNER JOIN controls for both aggregates return 60 and remain fully
pushed. The default join-pushdown-disabled path returns NULL correctly.

Root cause remains the effective-nullability boundary:
`DorisAggregatePushdownRules.kt:308` trusts `JdbcColumnHandle.isNullable`.
Trino 484 `DefaultJdbcMetadata.createSyntheticJoinProjectionColumn` still uses
`JdbcColumnHandle.builderFrom(column)` and changes only the column name, without
marking the null-supplying side nullable. It therefore preserves a base NOT NULL
flag that is invalid after the outer join. Doris skips NULL payloads while
Trino's `min_by`/`max_by` can select them.

**Fix direction, not implemented:** propagate effective nullability through the
metadata join result or conservatively reject the affected aggregate/outer-join
composition. Preserve eligible base-table and matched INNER JOIN controls.
Keeping join pushdown disabled avoids the measured exposure meanwhile.

### R-05: reproduced HA execute-phase abort blocking

Measured the actual `DorisPreparedStatement` wrapper, normal Connector/J 26.7.0
connections with production connection properties, and `SELECT sleep(4)`.
The actual helper was either disabled or failed its connection factory; failed
helper cases confirm one attempted helper connection. All multi-host URLs
name the **same FE twice**, isolating proxy locking rather than FE routing.

Final-run cancellation latency (whole milliseconds):

| URL topology | Helper disabled | Helper fails | Adopted stream, helper disabled |
|---|---:|---:|---:|
| Single host | 0 | 1 | 0 |
| Comma-list failover | 3996 | 3996 | 0 |
| Load balancing | 3996 | 3996 | 0 |
| Replication | 3996 | 3997 | 0 |

The first run's six HA execute-phase cases ranged from 3994 to 4006ms, agreeing
with the second run. Concurrent comma-list close waited **3745ms** behind a
cancellation taking **3996ms**. Single-host execution abort and all four
already-adopted streaming controls return promptly at this time resolution.

Cancellation stacks captured after 250ms show `WAITING` on
`ReentrantLock$NonfairSync`, owned by the executing worker, at Connector/J
`MultiHostConnectionProxy.invoke:497`. In the 26.7.0 source this lock is acquired
**before** the `abort(Executor)` dispatch at lines 515–519. Its JDBC statement
proxy also holds that lock across the delegated execution at lines 106–119.
The synchronous call at `DorisPreparedStatement.kt:80` therefore cannot be an
independent socket fallback while the HA statement owns the lock.

Post-abort streaming `ResultSet.close` attempts to drain a closed socket and
raises SQLException on this driver. The first diagnostic run stopped its
stream controls at that cleanup exception. The final probe records these
teardown exceptions separately and checks cancel latency for all four URLs;
it does not claim exception-free cleanup or change production behavior.

**Fix direction, not implemented:** capture and validate a physical abort target
before execution, including connection/failover identity, or prove another
independent socket-close path. Merely moving proxy abort to an executor does
not release the proxy lock and does not fix the cleanup wait. Verify helper
disabled/failing, execute, adopted stream, and concurrent-close schedules.
This same-FE experiment does not qualify cross-FE KILL forwarding or failover.

Raw diagnostic logs: `r04-r05-probes.log`, `r04-r05-probes-rerun.log`; original
and final XML/stack/plan captures: `r04-r05-xml/` and `r04-r05-rerun-xml/`.
`review-results.json` retains the final per-case observations. Relevant upstream
sources are retained under `upstream/` alongside the evidence.

## Release assessment

Do not release this baseline as correctness-safe yet. The new default-path
LOWER/UPPER divergence needs a guarded/local fallback or equivalent proven
rendering; R-04 and R-05 remain confirmed open findings. The version/metadata
lock and JSON/installation checks are complete, but are not a green release
qualification. No R-04/R-05 production fix, commit, push, tag, or release was
made. Other recorded review findings and optional coverage remain separate.

At the end of the recheck, the validation FE/BE and manual Trino containers
were stopped, restoring their initial stopped state. Named volumes and fixture
data were retained; unrelated build/substrate containers were not stopped.
