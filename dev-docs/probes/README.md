# Opt-in R-04 / R-05 rechecks

These diagnostic sources are **not part of the default build**. They assert the
correctness/non-blocking behavior we want, so an unfixed finding deliberately
produces a red probe run; it must not be reported as a green release gate.

After qualifying the digest-pinned Trino 484 / Doris 4.1.5 baseline:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew \
  -I dev-docs/probes/review-probes.init.gradle test \
  --tests '*TestDorisR04Review' --tests '*TestDorisR05Review' \
  --no-daemon --console=plain
```

Both probes use the connector's stock validation target at `127.0.0.1:9130`.
They require the golden native FE/BE version before running. R-04 recreates only
its `r04_484_review.l` and `.r` fixture tables. Do not point this harness at a
production server. No multi-FE failover or unrelated container is stopped.

- R-04 compares LEFT and RIGHT joins with both `min_by` and `max_by` under EAGER
  join pushdown, default-disabled join pushdown, and all-connector-pushdown OFF.
  Base-table and matched INNER JOIN controls must remain fully pushed and return
  60. EXPLAIN output is retained with the per-case values in JUnit XML.
- R-05 uses the real production prepared-statement wrapper and Connector/J
  connections. Single-host, comma-list, load-balancing, and replication URLs
  target the **same FE** (duplicate hosts isolate the proxy-lock behavior).
  Execute-phase probes run bounded `sleep(4)` work with the actual cancellation
  helper disabled or failing its connection factory. A 250ms observation captures
  cancellation thread stacks before letting the operation finish naturally.
  Adopted streaming results and concurrent close are checked separately.

The 1000ms diagnostic threshold is not a production latency SLA. A slow result
must be interpreted alongside the stack, query duration, and single-host/stream
controls. These same-FE URL probes do **not** establish cross-FE forwarding,
master election, or load-balancer routing behavior. Failed assertions and raw
JUnit XML are retained as evidence, not silenced or folded into the green
baseline count.
