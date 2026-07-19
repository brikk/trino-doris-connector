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

1. **DONE 2026-07-19** (`TestDorisTimezoneAudit.testPushedTemporalLiteralsAreZoneIndependent`
   + `testJvmDefaultZoneDoesNotLeakIntoBinding`). Matrix proven: {UTC, America/New_York,
   Asia/Tokyo} Trino session zones × {default, Asia/Tokyo, America/New_York}
   `doris.time-zone` (extra catalogs) × JVM default zone mutated to Pacific/Kiritimati (+14)
   and America/New_York mid-test. DST-adversarial wall clocks (2026-03-08 02:30 nonexistent
   in NY; 2026-11-01 01:30 ambiguous) and the 0000-01-01/9999-12-31 date edges: results
   byte-identical everywhere AND the audit-log literal is the exact zoneless wall-clock text
   (`'2026-03-08 02:30:00.123456'`, `'0000-01-01'`) in every zone. Zoneless passthrough PINNED.
2. **DONE 2026-07-19** (`testDatetimePathsAreSessionZoneInvariant`). Domain pushdown,
   ARRAY<DATETIME(6)> wire decode (UNNEST element compare), min/max(datetime) aggregate,
   datetime TopN, and the scalar 0000/9999 edge reads — byte-identical under NY/Tokyo session
   zones and through a Tokyo `doris.time-zone` catalog. No diffs.
3. **DONE 2026-07-19** (`testDorisTimeZoneCanaryAcrossEveryPushedFamily`). One representative
   per pushed family (domains, contains, array_position, cardinality, json, GUARDED
   LIKE-prefix, aggregates, EAGER join) result-identical across `doris.time-zone` ∈
   {default, Asia/Tokyo, America/New_York}. Guards future rule additions — re-run when a
   family lands. NO surprises found in any of items 1–3; no zone-dependence exists in the
   current pushed surface.
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
