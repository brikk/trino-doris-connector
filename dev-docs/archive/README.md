# Retained development records

These are frozen plans, observations and work logs. Their version locks, counts,
TODO/status text and environment paths describe their recorded checkpoints;
they are **not current usage instructions or an active backlog**.

Use the [current developer index](../README.md), [validation](../VALIDATION.md)
and [backlog](../BACKLOG.md) for today's state. The original content is retained
for rationale, counterexamples, stable review IDs and reproducibility. Local
links are relocated with the files; no probe result is rewritten into a new
qualification claim.

## Qualification and review

- [Runtime contracts: R-11/R-17/R-08/R-09 and final staged qualification](REPORT-trino-484-runtime-contracts.md)
- [Cancellation targeting and disposable harness](REPORT-trino-484-cancellation-harness-safety.md)
- [Release hardening](REPORT-trino-484-release-hardening.md)
- [Unicode, outer joins and physical read safety](REPORT-trino-484-release-safety.md)
- [Pre-fix Doris 4.1.5 measurements](REPORT-trino-484-doris-4.1.5-lock.md)
- [Trino 484 upgrade](REPORT-trino-484-upgrade.md)
- [Doris 4.1.4.1 upgrade](REPORT-doris-4.1.4.1-upgrade.md)
- [Consolidated review and stable R/E/Q/D/K IDs](REVIEW-2026-09-05.md)
- [Runtime triage with subsequent scoped resolutions](TRIAGE-trino-484-remaining-review.md)

## Native observations and design evidence

- [Protocol/type probe](REPORT-protocol-type-probe-doris-4.1.3.md)
- [Array wire-decoder spike](REPORT-array-wire-decoder-spike.md)
- [String comparison probe](REPORT-string-comparison-probe-4.1.3.md)
- [Temporal range probe](REPORT-temporal-range-pushdown-4.1.3.md)
- [FLOAT special values](REPORT-float-special-values-4.1.3.md)
- [IP pushdown probe](REPORT-ip-pushdown-probe-4.1.3.md)
- [Statistics validation](REPORT-statistics-validation-4.1.3.md)
- [Multi-FE failover](REPORT-multi-fe-failover.md)
- [Plugin assembly proof](REPORT-plugin-assembly-proof.md)
- [Stock MySQL connector comparison](REPORT-stock-trino-mysql-vs-doris-4.1.3.md)
- [Inverted-index EXPLAIN/profile capture](evidence/inverted-index-explain-p2b.md)
- [Type/capability ledger](LEDGER-p0-type-and-capability.md)
- [StarRocks connector audit](LEDGER-starrocks-trino-connector-audit.md)

## Implementation journals

- [Read path](NOTES-p1a-implementation.md), [read-only layers](NOTES-p1b-implementation.md)
- [Array decoding](NOTES-p2a-implementation.md), [array predicates](NOTES-p2b-implementation.md)
- [TopN/composition](NOTES-p3-implementation.md)
- [String modes](NOTES-p4-string-pushdown.md), [aggregates](NOTES-p4-aggregates.md)
- [JSON/cardinality/prefix batch](NOTES-p5-batch.md), [joins](NOTES-p5-joins.md), [statistics](NOTES-p5-statistics.md)
- [Date projections](NOTES-p6-date-projection.md)
- [Scalar expansion](NOTES-readonly-max-batch1.md), [aggregate expansion](NOTES-readonly-max-batch2.md), [DISTINCT/HAVING](NOTES-readonly-max-batch3.md)
- [Cancellation mechanism](NOTES-cancel-cluster-scoped.md)
- [Fixture bootstrap](NOTES-ci-fixture-bootstrap.md)

## Superseded plans, TODOs and handoffs

- [Founding plan](PLAN-trino-doris-extension.md)
- [Status snapshot](STATUS-2026-07-19.md)
- [Evaluation TODO ledger](TODO-rectify-from-eval.md): completed/refuted entries retained;
  unresolved requirements/qualifications are carried in the current backlog.
- [Timezone audit checklist](TODO-timezones.md): completed cases retained; ongoing
  admission and TIMESTAMPTZ constraints are carried in current invariants/backlog.
- [Parked ADBC proposal](TODO-adbc-arrow-flight.md): not implemented; its constraints
  remain explicitly deferred in the current backlog.
- [Fulfilled metadata API request](HANDOFF-brikk-sql-metadata-request.md)
- [Metadata API audit](REPORT-brikk-metadata-api-audit.md)
- [Original Compose guide](compose-README.md)
- [Original manual-sandbox transcript](compose-trino-README.md)
