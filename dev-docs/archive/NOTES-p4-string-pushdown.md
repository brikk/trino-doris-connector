# NOTES — P4 string pushdown modes implementation

Companion to `REPORT-string-comparison-probe-4.1.3.md` (the evidence) and
`TestDorisP4StringPushdown` (the live pins). Baseline: Doris 4.1.3-rc02 + Trino 483.

## What was built

- `DorisStringPushdownMode` enum: `NULL_ONLY`, `GUARDED` (default), `BINARY`, `FULL`;
  `allowsFullStringPushdown` = BINARY|FULL.
- Catalog config `doris.string-pushdown.mode` (`DorisConfig`) + session property
  `string_pushdown_mode` (`DorisSessionProperties.getStringPushdownMode`) — session
  overrides catalog in BOTH directions (tightening and loosening are tested).
- `DorisTypeMapping`: `VARCHAR_PUSHDOWN` / `CHAR_PUSHDOWN` `PredicatePushdownController`s
  dispatch on the session mode. GUARDED returns
  `DomainPushdownResult(simplifiedDomain, originalDomain)` — pushed superset pre-filter,
  exact Trino filter RETAINED (the STOCK ledger row-b pattern, now deliberate); domains
  whose values contain a 0x00 byte are skipped entirely (defense-in-depth, see below);
  CHAR never pushes values in GUARDED.
- `DorisClient.isPushableSortKey(session, type)`: VARCHAR sort keys TopN-eligible iff
  `allowsFullStringPushdown`; CHAR keys never (Trino orders trimmed values, Doris orders
  stored bytes — divergent for trailing-space data, undetectable from the query).
- `RewriteStringLike`: enabled in BINARY/FULL only. Two-arg `$like(col, 'pat')` doubles
  backslashes (Doris's default LIKE escape is `\`; Trino's no-escape LIKE treats `\` as a
  literal). Three-arg `$like(col, 'pat', 'x')` renders `LIKE ? ESCAPE ?` unchanged (Doris
  honors ONLY the declared escape char — probe-proven). varchar/string columns only, no
  CHAR; non-null varchar constants only; patterns containing NUL are not pushed.

## Findings worth remembering

1. **G5 is resolved by evidence, not assumption**: `@@collation_connection =
   utf8mb4_0900_bin`; every comparison shape probed (case, padding, NFC/NFD, emoji,
   zero-width, tab, long strings, ranges, IN, BETWEEN) is byte-exact == Trino VARCHAR.
   Byte order == codepoint order makes VARCHAR TopN safe. Hence **BINARY needs no forcing
   mechanism** (`BINARY x` and `COLLATE utf8mb4_0900_bin` exist but are unnecessary;
   `CAST(x AS BINARY)` is rejected) — BINARY and FULL share one rendering; BINARY is the
   verified-contract label, FULL the caller-asserted one. The suite's semantics pins fail
   loud if a Doris upgrade changes collation behavior.
2. **CHAR is the real hazard**: Doris stores CHAR UNPADDED and compares stored bytes;
   Trino CHAR compares trimmed values. Stored `'a '` misses `c = 'a'` remotely but matches
   in Trino — an under-return that lives in the DATA, undetectable from the literal.
   ⇒ CHAR excluded from GUARDED value pushdown and from TopN keys in all modes; in
   BINARY/FULL it pushes as a documented, tested divergence.
3. **The NUL saga**: the original probe recorded ONE wrong-empty result for a bound
   `'a\0b'` equality. It never reproduced — same URL, both fixtures, param and literal
   forms, and the connector FULL path all return the row byte-exactly. The miss coincided
   with the host-memory regime that later had the BE failing everything with
   `MEM_ALLOC_FAILED`. Verdict in the report: byte-exact; GUARDED keeps the 0x00 domain
   scan as defense-in-depth because skipping is always correct and a silent wrong-empty
   was observed once. Do NOT re-promote this to "Doris mishandles NUL" without a
   reproduction.
4. **Wildcard-free LIKE never reaches the connector's LIKE rule** — Trino folds
   `LIKE 'a\b'` (no `%`/`_`) into an equality DOMAIN at planning time; it ships through
   the domain path as `` `v` = 'a\\b' ``. LIKE-shape audit assertions must use patterns
   with wildcards (`'a\%'` → remote `'a\\\\%'`).
5. **Doris truncates CHAR at NUL on write** (`a\0b` → `61`); VARCHAR/STRING store
   byte-exactly (`610062`). Read-only connector: storage note, not a read hazard.

## Ops fallout (host memory)

The BE fails ALL queries with `MEM_ALLOC_FAILED` when HOST available memory drops under
`max_sys_mem_available_low_water_mark_bytes` (default ~1.6% of host = 1.33GB here) — it
reads /proc, not cgroup. Fixed for this dev box: `268435456` in `compose/be.conf` (and
`.be.conf.runtime`), `docker restart trino-doris-be`. Test JVM `maxHeapSize` 1536m,
gradle daemon Xmx2g. If `trino-doris-fe` is OOM-killed again: `./compose/up.sh` (volumes
persist fixtures).

## Test surface

`TestDorisP4StringPushdown` (12): fixture `p4_strings.t` (VARCHAR/CHAR/STRING columns,
adversarial rows incl. NUL, NFC/NFD, emoji, ZWSP, `%`/`_`/`\` literals, NULL row) built
with parameterized inserts (`DorisTestCluster.executePreparedAsRoot` added for NUL-safe
fixtures). Covers: mode wiring, GUARDED superset+retained shape (audit-log wire
assertions), GUARDED≡NULL_ONLY differentials over every hazard shape, hazard skips,
BINARY/FULL full-pushdown + byte-exact differentials, FULL divergence pins (CHAR
trailing-space; NUL non-divergence), session override both directions, TopN eligibility
per mode + ordering differential (the byte-vs-codepoint proof), LIKE per mode with
backslash-doubling and ESCAPE passthrough wire assertions.
`TestDorisRemoteSql.testStringEqualityWireShapePerPushdownMode` pins the default-GUARDED
wire shape. Totals after P4 strings: **143 tests / 18 suites**, all green.
