# TODO — optional ADBC / Arrow Flight SQL data plane

Requested 2026-07-19. **NOT FOR NOW** — recorded for a future phase (this is the founding
plan's parked P7/G8 item, sharpened into a concrete shape).

## The idea

Doris exposes an Arrow Flight SQL endpoint alongside the MySQL protocol. An ADBC-style
driver would let the connector stream **columnar Arrow batches** from the BEs instead of
row-encoding everything through the FE's MySQL wire — a substantially faster path for
large result sets (no per-row protocol encode/decode, batch transfers, and the FE stops
being the single serialization funnel).

**Proposed shape: dual data plane, chosen per catalog by the user.**

```properties
# default — works everywhere, correctness reference
doris.data-plane=jdbc
# opt-in — direct BE reachability required
doris.data-plane=adbc
```

- The metadata/control plane stays MySQL/JDBC in both modes (schemas, stats, audit
  tagging, cancellation — all proven there).
- Only the *result transport* switches: `jdbc` streams through Connector/J as today;
  `adbc` fetches the scan's result via Arrow Flight SQL from the BEs.
- Pushdown/SQL generation is shared — the remote SQL is identical; only who executes the
  fetch differs. Differential tests run every suite in both modes to hold the two planes
  to byte-identical results.

## Why it is not now

- **Network topology**: Flight clients need **direct BE reachability** (the FE hands back
  BE endpoints). Fine in "direct mode" deployments; painful in k8s/NAT setups where only
  the FE is exposed. That is exactly why it must be opt-in per catalog, never default.
- Doris marked Arrow Flight SQL experimental at our pinned 4.1.3; parameterized prepared
  statements and multi-BE parallel result delivery had known gaps (plan G8/P7).
- Type fidelity must be re-proven on the Arrow path from scratch: the entire evidence
  ledger (LARGEINT, DATETIME zonelessness, DECIMAL(76), ARRAY wire shapes, NULL
  semantics) is MySQL-wire evidence and transfers to Arrow only after a dedicated probe.
- Cancellation/timeout contracts need their own Flight-side proofs.

## When picked up, the P0-style gate is

1. Re-probe against the then-current Doris release: Flight endpoint stability, auth,
   prepared params, type round-trips (esp. the ledger's hazard types), multi-BE streams,
   cancellation.
2. Benchmark honestly: JDBC vs ADBC on the same scans (rows/sec, CPU, FE load) — the
   speedup claim is expected for large row counts but must be measured, not assumed.
3. Keep JDBC as the correctness reference and fallback; `adbc` mode failing must fail
   loud, never silently fall back with different semantics.

Code anchor: `DorisClientModule` builds the (single, JDBC) connection factory today — the
data-plane switch would live there. A pointer comment exists at that site.
