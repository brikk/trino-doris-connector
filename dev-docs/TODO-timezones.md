# TODO — time/date/timestamp/timezone audit

Requested 2026-07-19. The trino-ducklake / doris-ducklake plugin work surfaced a whole family
of timezone hazards at engine boundaries (session-zone-dependent function evaluation, zone
shifts on the JDBC/driver paths, TIMESTAMP WITH TIME ZONE conversion at the engine seam).
This note tracks the analogous surface in trino-doris: what is already defended with
evidence, and what still needs a dedicated audit.

## Exposure assessment: smaller here, but not zero

The structural reason this connector is less exposed than ducklake was: **Doris DATETIME is
zoneless** and maps to Trino's zoneless `TIMESTAMP(p)` — there is no instant conversion at
the type boundary in v1, and no `TIMESTAMP WITH TIME ZONE` column is ever exposed.

### Already defended (probe-backed, tested)

- **Reads**: DATETIME is read via `getObject(LocalDateTime)`; `getTimestamp` is **banned**
  (P0 probe: it shifts the value by the JDBC session zone and throws `YEAR` on `0000-*`
  values). Same rule inside ARRAY<DATETIME> decoding (wire text parsed directly).
- **`doris.time-zone`** is applied at Doris *session* scope only (never global), value
  validated before rendering into `SET time_zone=...`.
- **No zone-sensitive expression is pushed**: we push no `now()`/`current_*`/`unix_timestamp`
  family functions; date/datetime predicates travel as domains (zoneless literals), and
  min/max(datetime) aggregates return the column's zoneless wire text.
- Doris TIMESTAMPTZ (version-gated upstream) is **not exposed** — plan §5 keeps it out until
  a pinned-zone + DST/offset boundary contract exists.

### Open audit items (the actual TODO)

1. **Parameter-binding zone safety (highest value).** Predicate pushdown binds datetime
   literals through `StandardColumnMappings.timestampWriteFunction` /
   `dateWriteFunctionUsingLocalDate` under Connector/J client-side prep emulation. Prove the
   rendered literal is zone-independent with a matrix test: {Trino session zone} ×
   {`doris.time-zone`} × {JVM default zone} × {DST-boundary dates, incl. a nonexistent and an
   ambiguous local time}. Expect zoneless passthrough; pin it.
2. **Session-zone variation suite.** All current differentials run in one fixed zone. Re-run
   the key datetime differentials (domain pushdown, ARRAY<DATETIME>, min/max/TopN on
   datetime) under 2–3 Trino session zones and a non-UTC `doris.time-zone` — results must be
   byte-identical (zoneless types ⇒ zone changes must be a no-op; any diff is a bug).
3. **`doris.time-zone` canary.** One test asserting that changing `doris.time-zone` does NOT
   change any currently-pushed query's results (it should only matter for zone-sensitive
   Doris functions we deliberately don't push). Guards future rule additions.
4. **Future scalar rules must carry a zone proof.** If/when `date_trunc`, `year`/`month`,
   `unix_timestamp`/`from_unixtime`, or `str_to_date` pushdown lands (candidates list), each
   rule needs an explicit zone-semantics proof — several of these ARE zone-sensitive in Doris
   (they consult the session `time_zone`). This is where ducklake-style pain would re-enter.
5. **TIMESTAMPTZ revisit trigger.** When Doris's `TIMESTAMPTZ` stabilizes upstream, the
   mapping needs: pinned connection zone, instant-vs-wall-clock contract, DST/offset boundary
   fixtures, and a decision on `TIMESTAMP WITH TIME ZONE` precision. Do not expose before
   that contract exists (plan §5 stance).
6. **DATE boundary sanity under zones.** `0000-01-01` / `9999-12-31` edges are proven in one
   zone; include them in the item-2 matrix (they're where driver-side zone conversion bugs
   typically bite first).

## Where the prior art lives

The ducklake-side timezone findings (Trino↔DuckDB via the ducklake plugin) live in the
ducklake repos' dev-docs/friction logs — consult them before implementing item 4; the
categories of failure (session-zone-dependent evaluation, driver conversion shifts,
engine-boundary instant reinterpretation) map 1:1 onto the risks listed above.
