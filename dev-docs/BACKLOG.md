# Developer backlog and retained decisions

The prioritized **R-01–R-17 findings are resolved within their documented
scopes**. Completed/refuted work is not an active TODO. Original evidence,
stable IDs and qualifications remain in the
[archived review](archive/REVIEW-2026-09-05.md) and
[evaluation ledger](archive/TODO-rectify-from-eval.md).

This is **not an automatic before-release fix list**. These items are unresolved
requirements, limitations, robustness questions or optional improvements. A new
implementation batch needs an explicit scope; do not equate every item with a
confirmed valid-data wrong-answer bug or silently call it fixed.

## Robustness and additional validation

| IDs | Remaining knowledge / acceptance criteria |
|---|---|
| E-10, E-25, E-33, Q-04 | Choose strict-failure versus per-column fail-soft policy for malformed authoritative type metadata. Test single-argument decimals, empty/negative arguments, trailing text and malformed shapes. Unknown normal base names already become unsupported. A made-up overflowing VARCHAR declaration is not proof a valid Doris server emits it; do not promise every catalog query fails or silently hide columns without a policy decision. |
| E-12 | Probe malformed UTF-8 Slice admission, especially `starts_with`; consider rejecting unsafe slices. The broad LIKE claim is unproven because Trino LIKE and JDBC string binding also pass through strings. Do not describe the three-argument path as byte-safe merely because it retains a Slice parameter. |
| E-25 | Unexpected expression arity/assignment casts should reject cheaply where feasible; nullable Optional and unused projection dependencies merit small cleanup. Aggregate rules currently accept variables, so discarded parameter lists are not evidence of lost bound values. Case-sensitive truncation units lose optimization, not demonstrated correctness. Public Kotlin classes/FQN style are hygiene, not blanket runtime defects. URL/null validation is addressed by R-17, not an outstanding NPE claim. |
| K-03 | Reject malformed NaN/infinite statistic fields independently so one bad record need not discard healthy estimates through the outer fail-soft path. Keep malformed-input behavior separate from valid snapshots. |
| Q-10 | Strengthen IP TopN's independent oracle: disable all relevant pushdown, use an actually selective LIMIT and compare ordered results. Existing full-pushdown assertions/differentials are useful; this is not absence of all coverage. |
| Q-02 | Keep composition regressions as a standing requirement, not just ordinary examples of isolated rules. |

## Operational limits and measurements

| IDs | Current boundary / work only if that contract is expanded |
|---|---|
| K-01 | FLOAT/DOUBLE wire text may lose stored precision or render finite extrema as infinity. Local evaluation cannot reconstruct discarded bytes. No lossless-binary claim for scalar/array elements. |
| K-02, Q-07 | Statistics can combine mixed-age nonzero counts/NDV/nulls. R-08 is cache/session policy, not freshness. Measure realistic stale snapshots and metadata/statistics round trips before accuracy/scaling claims or optimization. |
| K-04, K-05 | Establish an absolute setup deadline contract separately for DNS, credentials, authentication/custom sockets, remote TLS stores and silent/progressing greetings. The helper watchdog starts after connection opening; normal read connections lack a universal setup/read deadline before the wrapper. Positive normalized R-11 settings do not solve all of this. |
| K-06 | Build a real restricted-account/per-host credential/rotation cross-FE cancellation matrix. Preserved properties and root-account forwarding tests do not prove helper identity/permissions in every deployment. Reconnecting probes retain this best-effort boundary. |
| K-07, K-08, Q-05 | Maintain the protected no-argument scan execution seam, read-only SPI classification and absence of arbitrary-query entry points during Trino upgrades. Vendor unwrap/delegated methods are not all covered by the scan wrapper; no current SQL-user escape was identified from that fact. The legacy join SPI deprecation is upgrade debt, not a reason to suppress warnings without review. |
| K-09 | Distinguish FE cancellation processing/logs from independently measured BE resource reclamation. Add the latter before making a stronger operational claim. |
| Q-06 | One remote statement per scan is an intentional distributed-execution/streaming tradeoff, not a confirmed missing-split defect. Benchmark before changing transport/parallelism. |
| Q-08 | Expose actionable accepted/rejected/expired/timeout/queue metrics for operators; current diagnostics are mostly internal/static and failures often DEBUG. Do not imply observability itself fixes connection deadlines. |

## Optional engineering and automation

- **E-16:** registry tuple/SHA checks are real; handwritten rendering/guard strings
  are documentation consistency checks, not instantiated SQL-rendering proof.
  Current guides state that boundary. Additional direct rule-output tests remain
  useful; do not remove the sanctioned runtime gate as routine cleanup.
- **E-18 / E-26:** modes, scalar transformations, comparisons, grouping and the
  standard expression/all-pushdown switches have different scopes. E-26's claimed
  missing switch is refuted, not a new feature request. Keep their documentation
  aligned rather than adding a duplicate control.
- **E-20's policy distinction:** native STARTS_WITH exists, but escaped LIKE is an
  intentional restricted prefix rendering; it need not share arbitrary LIKE's
  admission threshold. The stale prose/index description was corrected, not used
  as an excuse to broaden either gate.
- **E-24:** share duplicated invariants where it prevents drift, but keep distinct
  predicate-only/value-safe tiers. Do not turn cleanup into an abstraction rewrite.
- **E-29 / Q-09:** scheduled slow/overlay lanes and automated real-plugin smoke
  remain optional. Current CI has substantial default cancellation coverage and
  explicit local lanes; it does not run every lane. Preserve historical intermittent
  trace-id probe evidence rather than calling an isolated rerun a green full suite.
- **E-30:** improve unit/live separation/preflight experience without silently
  skipping required integration tests. R-15 safeguards are present, but arbitrary
  endpoint overrides are not an approved feature or a bypass of identity checks.
- **E-32:** action SHA pinning, explicit minimal CI permissions, signing/attestation
  and automated smoke are separate hygiene decisions. R-13's ABI-label gap is fixed;
  same-job checksums remain valid corruption checks. No auto-generated-release-note
  behavior is promised by the current workflow.

No scheduled job, new runner/secret or publication trigger is authorized by this
backlog. Existing release mechanics are in [Releasing](RELEASING.md).

## Ongoing admission and deferred architecture

- **Timezone obligation (D-01):** existing date projection, truncation, extraction,
  domain, array, grouping/aggregate and ordering paths have zone/edge regressions.
  Every added temporal family still needs explicit session/JVM/Doris-zone and
  boundary proof; the requirement is ongoing, not a completed universal audit.
- **TIMESTAMPTZ:** not exposed. Reconsider only with a deliberate instant versus
  wall-clock contract, precision decision, pinned connection zone and DST/offset
  fixtures. No dependency/server upgrade is automatically requested by that idea.
- **K-10 / metadata:** recheck semantics on explicit version changes; a startup
  registry tuple is not dynamic server certification. Keep LOWER/UPPER local;
  [brikk-sql#1](https://github.com/brikk/brikk-sql/issues/1) is an evidence correction,
  not a consumer release dependency or permission to re-enable the rules.
- **E-28:** the featherweight runtime metadata dependency/fail-loud startup check
  is intentional. Moving it to tests is an architecture/product decision, not an
  accidental dependency to remove.
- **ADBC / Arrow Flight (D-02):** parked, not implemented or a release prerequisite.
  Retain JDBC as default/control plane and correctness reference; an opt-in result
  transport needs direct BE reachability (including NAT/k8s constraints), fresh
  auth/prepared-parameter/type/multi-BE/cancellation probes and honest same-scan
  throughput/CPU/FE-load benchmarks. Share SQL generation and require result
  equivalence; a failed alternate mode must fail loudly, not silently change
  semantics through fallback. The full proposal is [archived](archive/TODO-adbc-arrow-flight.md).
- **E-27:** the code's ADBC pointer is sanctioned deferred knowledge, not a runtime
  defect merely because its comment spelling differs from a lint pattern.

Completed timezone cases, the fulfilled metadata API handoff, E-19's superseded
case-fold label and E-31/R-15's target guard do not remain active checkboxes.
E-16's overclaimed prose and the comment-only E-20–E-23/D-06 inaccuracies were
corrected during documentation cleanup; additional rendering tests remain an
optional E-16 follow-up, not a claim the index itself proves equivalence.
The archived TODOs preserve their proof/history; none is an instruction to
implement writes, another data plane or an automatic upgrade.
