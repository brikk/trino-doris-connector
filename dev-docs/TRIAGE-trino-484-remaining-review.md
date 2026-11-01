# Remaining runtime, configuration and harness triage

Date: 2026-11-01. Scope: remaining R findings after the approved safety and
release-hardening batches, plus the operational K findings they touch. This
is triage, **not implementation or closure** of those findings. The golden
Trino 484 / Doris 4.1.5 / Connector/J 26.7.0 stack stays fixed.

**Subsequent R-10 resolution:** connector scan SQL now begins with its token;
matching requires that exact leading UUID comment. The literal false-positive
is rejected, and 37 cancellation checks pass. See
[cancellation/harness safety](REPORT-trino-484-cancellation-harness-safety.md).
Other findings below retain their original open status until explicitly updated.

## Open findings and next acceptance criteria

| Finding | Current exposure | Next scoped remedy / acceptance criteria |
|---|---|---|
| R-08 statistics session switch | Delegate check is below the shared table-statistics cache. Default zero cross-query TTL avoids the reported persistent hit, but positive TTL permits cross-session policy leakage. Optimizer estimates, not row values. | Enforce the switch above caching or partition cache identity by the policy. Test enabled→disabled and disabled→enabled, positive TTL and missing-value caching; preserve ordinary enabled hits. |
| R-09 helper capacity | Source still parks each executing helper in a 500ms sleep/retry loop for up to 15s until its token has been seen. Eight never-visible/early-finished attempts can consume the eight workers while genuine cancels queue. No new throughput benchmark is claimed. | Schedule delayed probes without holding scarce workers or an idle connection. Keep one original dispatch deadline, bounded outstanding work, delayed-visibility protection, and sibling isolation; test fairness under eight absent tokens plus a live ninth token. |
| R-10 cancellation candidate identity | The matcher searches arbitrary SQL text for a complete token comment, including inside an unrelated string literal. The per-statement UUID prevents accidental prefix collision but does not parse SQL context. | Restrict matching to a verified connector-owned SQL comment boundary or parse comments safely. Prove a literal/comment audit-search query cannot be killed, while genuine scans and generated SQL shapes remain cancellable. Do this before claiming the helper cannot cancel unrelated queries. |
| R-11 duration validation | `DorisConfig` setters still accept zero, sub-ms and out-of-range connect values; connection initialization rounds query timeout while scan setup clamps to one second. No shared supported-range policy. | Validate catalog and session values against one documented normalization/range contract; prove 0ms, 0.1ms, max integer ms, overflow, 100ms query timeout and upper bound. Fail at configuration rather than connection/scan time. |
| R-15 destructive target authorization | Golden native-version checking is helpful but not proof a matching server is disposable. A failed fixture fingerprint count can still cause `DROP DATABASE p0_probe`; many suites drop their own fixed schemas. Overlay enablement still uses port reachability before stopping a named container. No accidental loss observed. | Require disposable-cluster identity and explicit destructive/failover opt-in at the common boundary; CI opts in intentionally. Reject before any setup mutation or container stop. Do not add arbitrary endpoint overrides before this safeguard. |
| R-17 URL contract | Raw Connector/J URL acceptance is broader than the ordinary JDBC path; checking only the textual path misses effective DBNAME properties. | Admit supported JDBC types, reject X Protocol and effective global/per-host databases without DNS, connection or user-supplied property-transform execution during validation. Cover case/encoding, nested HA forms and side-effect freedom. |

R-10 and R-15 are safety blockers I recommend resolving before publication.
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
does not start the failover overlay. It does not fix R-15's general harness
authorization gap.

```sh
./gradlew -I dev-docs/probes/review-probes.init.gradle test \
  --tests '*TestDorisRemainingReview' --no-daemon --console=plain
```

Replay on the locked stack: **five diagnostic tests passed**, including the
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
`remaining-review-xml/`. R-09 and R-15 remain **source-confirmed** in the current
code, not newly load-tested or destructively reproduced. The diagnostic code
pins observed defects and must be replaced/updated as their fixes land.

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
