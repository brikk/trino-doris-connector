# NOTES — P2a implementation (strict native ARRAY support)

Status: P2a complete. `./gradlew :trino-doris:build` green (91 tests / 10 suites + detekt +
assembly verification). Builds on P1b (a81629b); coded against PLAN G4/§5 "ARRAY
implementation gate"/§10-P2, ledger §A ARRAY rulings, and
`REPORT-array-wire-decoder-spike.md` (the checked-in spike is evidence; this is a fresh
production implementation). The brikk evidence-registry work stays PARKED — P2a is the pure
wire/type layer.

New classes: `DorisArrayElement` (sealed allowlist descriptor), `DorisArrayWireDecoder`
(strict parser + Block builder). Extended: `DorisTypeMapping` (array mapper + recursive
element resolver + shared `ipAddressSlice`/`timestampType` helpers),
`DorisTypeMapping.arrayElementColumnType` (nesting-aware `array<...>` COLUMN_TYPE parsing).
New tests: `TestDorisArrayWireDecoder` (13 unit), `TestDorisP2aArray` (12 live, differential
vs the Doris oracle). Updated: `TestDorisTypeMapping`, `TestDorisP1aSmoke` (arrays are no
longer hidden).

## Decoder design

- **Two layers.** `DorisArrayWireDecoder.decode(wire, element)` is a strict recursive-descent
  parser over the PROVEN grammar (spike §2): `[` `]`, separator exactly comma+ONE-space, bare
  `null` token with boundary guard, bare numerals/`1`/`0` booleans, double-quoted UNESCAPED
  content for DATE/DATETIME/IP. It emits **Trino native carriers** directly (Long for
  integers/epoch-day/epoch-micros/float-bits/short-decimal-unscaled, Int128 for LARGEINT and
  long DECIMAL, Boolean, Double, Slice for IPADDRESS, nested List). 
  `DorisArrayWireDecoder.elementsBlock(element, values)` then builds the Trino elements Block
  (only the PostgreSqlClient Block-construction pattern is borrowed — Connector/J implements
  no `java.sql.Array` for Doris, spike §6), recursing for nested arrays and using
  `TypeUtils.writeNativeValue` for leaves.
- **Fail-loud, no permissive fallback** (spike F6): every violation throws `TrinoException`
  with the wire offset and truncated wire text — unterminated array/quote, missing space
  after comma, empty bare token, trailing garbage, malformed/overflowing numerals, boolean
  not 1/0, malformed dates, `null`-prefixed bare tokens, interior `"` in a quoted element
  (alphabet violation — the only place a `"` can legally appear is the structural close,
  because the content carries NO escaping).
- **`[]` vs SQL NULL vs NULL element** (spike F1): SQL NULL never reaches the decoder (the
  read function's isNull check handles it; the read function itself refuses a null wire
  string); `[]` decodes to an empty list; bare `null` to a null element.
- **Precision exactness**: LARGEINT via `DorisTypeMapping.parseLargeint` (BigInteger →
  Int128, **fail-loud out of DECIMAL(38,0)**, never clamp); DECIMAL via BigDecimal with
  **exact-only rescale to the declared scale** (trailing zeros preserved; finer-than-declared
  scale or precision overflow fails loud); DATETIME with microseconds preserved and
  finer-than-declared fractions rejected; arbitrary nesting depth (unit-tested to 4 levels;
  ≥3 proven live).

## Allowlist as implemented (ledger §A verdict, exact)

Native `ARRAY(T)` (pushdown DISABLED — ARRAY predicates are P2b):
- `array<tinyint(1)>` → `array(boolean)` (Doris BOOLEAN; wire `1`/`0`)
- `array<tinyint|smallint|int|bigint>` → matching integer arrays (range-checked)
- `array<largeint>` → `array(decimal(38,0))`, fail-loud out of range
- `array<decimalv3(p<=38, s)>` → `array(decimal(p,s))`
- `array<float>` → `array(real)`; `array<double>` → `array(double)` — **GO with the ledger F3
  caveat implemented as ruled**: wire `Infinity`/`-Infinity`/`NaN` tokens and the DOUBLE-max
  16-sig-digit rendering that reparses to Infinity are surfaced FAITHFULLY (the ledger
  mandates surfacing, not failing: "do NOT paper over with a silently-wrong finite value" —
  and the oracle suffers the identical wire artifact, proven in the live differential)
- `array<date>` → `array(date)`; `array<datetime(0..6)>` → `array(timestamp(p))`
- `array<ipv4|ipv6>` → `array(ipaddress)`
- `array<array<...>>` of allowlisted leaves, any proven depth

DENIED (never native; unsupported-type policy = hidden, or whole-array wire TEXT under
`unsupported-type-handling=CONVERT_TO_VARCHAR`, per ledger §A enforcement paragraph):
- `array<varchar|char|string>` — hard NO-GO, spike F4: zero escaping makes crafted values
  byte-indistinguishable from the delimiter grammar (`["a", "b"]` is one element `a", "b` OR
  two elements — proven byte-identical); cited in `DorisArrayElement` KDoc
- `array<decimalv3(p>38)>` (Trino DECIMAL ceiling), `array<json>` (not creatable, F5),
  `array<map|struct|variant>` (separate wire work), and any nested array whose leaf is denied
  (denial inherited, spike §7.3)

Allowlisted arrays stay native even under CONVERT_TO_VARCHAR (the policy governs only
unsupported types).

## Test inventory + results (all green)

- `TestDorisArrayWireDecoder` (13, unit, no cluster): every spike §3 fixture class with
  VERBATIM wire texts (ints+nulls+empty, integer ranges, LARGEINT exact/fail-loud, DECIMAL
  trailing zeros incl. long-decimal Int128, booleans, float/double incl. F3 Infinity and NaN,
  DATE edges incl. year 0000, DATETIME µs/zero-fraction/precision-mismatch, IP round-trips,
  nesting to 4 levels), the full spike §5 malformed battery (12/12 fail loud with wire
  offset), null-token boundary guard, and Block construction sanity (flat + nested).
- `TestDorisP2aArray` (12, live vs `p2_array` fixtures, recreated per run): DESCRIBE type
  surface for all four fixture tables; **element-by-element differential vs the Doris oracle**
  (`array_size`/`element_at` CAST STRING over direct JDBC) for every allowlisted scalar
  element type including boundary rows; nested `[[1, null], null, []]` and 3-level arrays;
  `[]` vs NULL vs NULL-element distinction end-to-end; DOUBLE-max row surfaces
  ±Infinity/NaN faithfully; **LARGEINT element at ±(2^127−1) fails the query loudly**;
  string arrays hidden by default, exposed only as whole-array TEXT under CONVERT_TO_VARCHAR
  with the crafted ambiguity pair returning byte-identical text while the oracle proves the
  rows differ (silent mis-read impossible — no element-count claim is ever made).
- Updated suites: `TestDorisTypeMapping` (allowlist/denial/CONVERT matrix incl. nested),
  `TestDorisP1aSmoke` (p0_probe.arrays now exposes the allowlisted columns natively; string
  arrays remain hidden; CONVERT keeps a_int native and textualizes a_varchar50/a_string).

## Deviations from the spike discovered while productionizing (each with evidence)

1. **DATETIME fraction width is variable (1..6), not fixed 6.** The spike code parsed
   fractions with a fixed `.SSSSSS` pattern (only correct for datetime(6)); the report's own
   grammar note ("fraction width = declared scale") implies datetime(3) renders `.789`.
   Production uses a strict formatter accepting 1–6 fraction digits, and the live
   differential over `array<datetime(3)>` (`.789`, `.000`, `.999`) confirms it.
2. **No STRING element kind exists in production.** The spike carried a heuristic STRING
   branch (scan past interior quotes) purely to demonstrate the ambiguity; production removes
   it entirely — an interior `"` in any quoted kind fails loud, and string-family leaves are
   rejected in the type mapper before a decoder ever runs.
3. **Declared-scale enforcement for DECIMAL.** The spike returned raw `BigDecimal(tok)`;
   production rescales exact-only to the column's declared scale and checks precision,
   because Trino decimal blocks are fixed-scale. Wire evidence (trailing zeros preserved,
   spike F2) makes this a no-op for conforming wire text and a loud failure otherwise.
4. **Native carriers instead of boxed Java values** (spike returned Byte/Short/LocalDate/...):
   production emits Trino block carriers directly so the Block writer is a thin recursion.
   Range semantics preserved (TINYINT/SMALLINT/INT range checks match the spike).
5. **`NaN` in FLOAT arrays is live-creatable** (`ARRAY(CAST('nan' AS FLOAT))` → wire `[NaN]`),
   which the spike fixtures did not cover; the bare-token specials (`Infinity`/`-Infinity`/
   `NaN`) are accepted for REAL/DOUBLE exactly as the spike's strict parsers did, and the
   fixture row is in the live differential.
6. **Fixture syntax note**: Doris 4.1.3 rejects bracket array literals in `INSERT ... VALUES`
   (`no viable alternative at input '[CAST'`); the `ARRAY(...)` function form works and is
   what the fixtures use (probed live).

## P2b open items

- Typed predicate rules: `contains(array, v)` → `array_contains(...) = 1`,
  `arrays_overlap(...) = 1`, `array_position` comparisons (PLAN §6.2) — numeric arrays first;
  string-array index acceleration needs the separate superset-pre-filter design (spike §7.6),
  NOT native ARRAY<VARCHAR>.
- Doris inverted-index EXPLAIN/profile fixtures (PLAN §6.4): result equivalence +
  predicate-at-scan evidence gate P2; index selection/timings are recorded benchmarks.
- brikk evidence-registry drift tests — PARKED pending user approval of the
  `brikk-sql-metadata` additive API + non-SNAPSHOT release (ledger §F).
- ARRAY predicate pushdown controller currently DISABLE_PUSHDOWN; P2b revisits for the typed
  rules (domains over arrays stay disabled regardless).
- MAP/STRUCT native decoding remains deferred (separate wire work; text grammar observed but
  unproven for adversarial keys).
