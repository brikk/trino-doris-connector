# Remaining runtime, configuration and harness triage

Date: 2026-11-01. Scope: remaining R findings after the approved safety and
release-hardening batches, plus the operational K findings they touch. This
is triage, **not implementation or closure** of those findings. The golden
Trino 484 / Doris 4.1.5 / Connector/J 26.7.0 stack stays fixed.

**Subsequent R-10 resolution:** connector scan SQL now begins with its token;
matching requires that exact leading UUID comment. The literal false-positive
is rejected, and 37 cancellation checks pass. See
[cancellation/harness safety](REPORT-trino-484-cancellation-harness-safety.md).
**Subsequent R-15 resolution:** common fixture access now requires explicit
permission and verified disposable identity, and lifecycle actions use original
immutable IDs. The full single-FE run and separately authorized overlay replay
passed; an available overlay without failover permission stayed skipped.
**Subsequent R-11 resolution (2026-11-03):** one timeout range/round-up policy
now governs catalog/session validation and connection/scan rendering; 24 focused
checks pass. See [runtime contracts](REPORT-trino-484-runtime-contracts.md).
**Subsequent R-17 resolution (2026-11-03):** pure parser/type admission rejects
X Protocol, effective DBNAME and URL indirection without DNS/transform loading;
19 focused checks pass. **R-08/R-09 remain open.**

## Open findings and next acceptance criteria

| Finding | Current exposure | Next scoped remedy / acceptance criteria |
|---|---|---|
| R-08 statistics session switch | Delegate check is below the shared table-statistics cache. Default zero cross-query TTL avoids the reported persistent hit, but positive TTL permits cross-session policy leakage. Optimizer estimates, not row values. | Enforce the switch above caching or partition cache identity by the policy. Test enabled→disabled and disabled→enabled, positive TTL and missing-value caching; preserve ordinary enabled hits. |
| R-09 helper capacity | Source still parks each executing helper in a 500ms sleep/retry loop for up to 15s until its token has been seen. Eight never-visible/early-finished attempts can consume the eight workers while genuine cancels queue. No new throughput benchmark is claimed. | Schedule delayed probes without holding scarce workers or an idle connection. Keep one original dispatch deadline, bounded outstanding work, delayed-visibility protection, and sibling isolation; test fairness under eight absent tokens plus a live ninth token. |
| R-10 cancellation candidate identity | **Resolved in 6cc9824:** the exact UUID comment must lead connector-owned SQL. Literal, identifier, nested-comment and audit-search decoys do not match. | 37 focused checks pass, including production-helper no-wrong-KILL, genuine scans, retries and sibling controls. Deliberately forged leading tokens are not a cryptographic authentication guarantee. |
| R-11 duration validation | **Resolved:** connect 1–2147483647ms, query 1–2147483647s or unset; fractions round up identically in all paths. | Sub-unit/out-of-range catalog/session values fail before use; bounds, native sessions and actual scan setup pass. DNS/credentials/TLS absolute deadlines remain separate K-04/K-05 risks. |
| R-15 destructive target authorization | **Resolved:** common fixture access requires explicit mutation permission, current disposable Compose ownership/original IDs, and native ClusterId/FE/BE identity. Failover additionally needs its own explicit permission. | Seven pure guards, real fixture refusal, full single-FE build and separate real-overlay permission/failover replay pass. Lifecycle actions re-inspect and use immutable IDs, not mutable names or reachability. |
| R-17 URL contract | **Resolved:** supported JDBC families only; decoded path/global/per-host DBNAME checks. Nonempty profiles/transforms rejected without loading. | Case/encoding/HA/SRV/IPv6/malformed/side-effect sentinel and real catalog validation pass. This is not a sandbox for arbitrary custom driver code at connection time. |

R-10 and R-15 are resolved within their documented guardrail scope.
R-08/R-11/R-17 are concrete configuration-contract defects, not reasons to bump
Doris or relax semantic gates. R-09 merits a separately bounded scheduling
change, not a larger timeout or removal of the delayed-visibility race guard.
These are recommendations for the next authorized batch, not a release decision.

## Diagnostic replay

`dev-docs/probes/TestDorisRemainingReview.kt` is an opt-in **current-defect
diagnostic**, deliberately outside the default test source set. Passing its
assertions confirms a defect remains reproducible; it does not qualify the
connector as safe. It uses a UUID-named schema on the already identified local
validation cluster, cleans up only its own schema, issues no live KILL, and
does not start the failover overlay. Its connections now use the shared R-15
authorization boundary; the diagnostic itself is not that implementation.

```sh
DORIS_TEST_ALLOW_MUTATION=1 ./gradlew -I dev-docs/probes/review-probes.init.gradle test \
  --tests '*TestDorisRemainingReview' --no-daemon --console=plain
```

Historical replay before the R-10/R-15 fixes: **five diagnostic tests passed**, including the
framework naming check. The four substantive probes confirm remaining defects:

- **R-08, live:** positive TTL, enabled first → row count `1.0`; a disabled
  session then also receives `1.0`. Disabled first with missing-value caching →
  unknown; a subsequent enabled session stays unknown. Both directions are now
  live-reproduced, not just inferred from cache source.
- **R-10, classifier only:** `SELECT '/*<exact-statement-token>*/'` is classified
  as a kill candidate. No unrelated query was actually killed in this replay.
- **R-11, property construction:** `0ms` and `0.1ms` both become driver timeout
  `0`; `30d` becomes `2592000000`ms (outside the driver's integer range).
  `100ms` query timeout becomes connection initialization `query_timeout=0`,
  whereas scan source clamps to at least one second. No new end-to-end timeout
  or malicious-server deadline benchmark is claimed.
- **R-17, no-network validation:** `mysqlx://fe:9030` passes validation while
  the ordinary JDBC driver returns no connection for it. Both query-level
  `?DBNAME=hidden_db` and per-host `(DBNAME=hidden_db)` pass the no-database check
  while the driver's effective database is `hidden_db`. No DNS, socket, or
  custom properties transform was invoked by these controlled test URLs.

Log/XML: `~/.cache/trino-doris-484-hardening/remaining-review.log` and
`remaining-review-xml/`. That replay classified R-09 and R-15 as source-confirmed,
not load-tested/destructively reproduced. R-09 remains open. The R-10 diagnostic
has since been updated to require rejection of the literal case; the other
diagnostics still reproduce open defects, not release-safety successes.

## Operational risks kept separate from confirmed findings

- **K-01:** text-wire FLOAT/DOUBLE precision is not recovered by local evaluation.
  No new binary-fidelity claim or transport rewrite in this batch.
- **K-02/K-03:** mixed-age nonzero estimates and malformed statistic fields need
  freshness/isolation probes; they are not solved by the R-08 cache-policy fix.
- **K-04/K-05:** a socket connect timeout is not an absolute wall-clock setup
  deadline for DNS/credentials/custom TLS or a silent greeting. Physical pinning
  starts after connection opening, so R-05 does not close these risks.
- **K-06:** preserved helper URL properties are not a restricted-account,
  credential-rotation, cross-FE permission proof. A live identity matrix remains.
- **K-07:** the statement wrapper protects Trino's no-argument scan execution
  seam, not every vendor-unwrapped JDBC API. No new SQL-user escape identified.
- **K-08:** pin the absence of arbitrary query entry points on future Trino
  upgrades; read-only reflection and entry-point tests are distinct defenses.
- **K-09:** cancellation processing/logging is not independent proof that every
  BE resource is reclaimed. Do not conflate these observability claims.
- **K-10:** Unicode counterexamples have already driven local fallback; stale
  upstream identity evidence is now reported as
  [brikk-sql#1](https://github.com/brikk/brikk-sql/issues/1). No dependency update
  or automatic re-admission follows from that issue.

Default CI still omits the slow tagged and separate multi-FE lanes. Packaged
server smoke was performed manually in prior qualification; it is not yet a
CI/release gate (E-29/Q-09). Remaining parser/evidence/KDoc ledger items are
not silently closed by this runtime triage.
