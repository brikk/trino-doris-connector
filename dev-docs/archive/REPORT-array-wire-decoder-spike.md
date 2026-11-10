# P0 ARRAY wire-decoder spike — Apache Doris 4.1.3 via MySQL Connector/J 9.7.0

Executes the PLAN §5 "ARRAY implementation gate" / G4 / §12 P0 exit criterion:
prove (or disprove) native ARRAY decoding from Connector/J `getString()` text.
Every claim here is backed by live output in
`evidence/array-spike-raw-output.txt` and the checked-in spike decoder
`evidence/ArrayWireDecoder-spike.java`. This is checked-in evidence, not
ephemeral notes.

## Pinned context

| Component | Value |
|---|---|
| Doris FE/BE | `doris-4.1.3-rc02-7126cf65d96` (container `trino-doris-fe`) |
| MySQL-proto version | `5.7.99` |
| JDBC driver | MySQL Connector/J `9.7.0` |
| Spike JVM | OpenJDK `21.0.2` |
| Connection | `jdbc:mysql://127.0.0.1:9130/p0_array_spike?...&tinyInt1isBit=false&characterEncoding=UTF-8&sessionVariables=enable_decimal256=true` (user `root`, no password) |
| Fixture DB | `p0_array_spike` (+ `p0_array_probe0` for the escaping/ambiguity micro-probes) |

Oracle for every cell = Doris itself: `array_size(col)`,
`element_at(col, i)` cast to STRING, `hex(...)` and typed `element_at` for
floats. The decoder's own parse is never treated as truth.

---

## 1. VERDICT

**GO-WITH-RESTRICTIONS.** Native ARRAY is feasible in v1 **only** for an
element-type allowlist whose wire representation is unambiguous. String-family
element types are a hard **NO-GO** because Doris emits their array text with
**zero escaping**, making crafted values provably indistinguishable from the
array delimiter grammar.

### Proposed v1 element-type allowlist (native ARRAY<T>)

| Allowed (GO) | Denied in v1 (NO-GO / defer) |
|---|---|
| `ARRAY<TINYINT>` | `ARRAY<VARCHAR>` — ambiguous, unescaped |
| `ARRAY<SMALLINT>` | `ARRAY<CHAR>` — ambiguous, unescaped |
| `ARRAY<INT>` | `ARRAY<STRING>` — ambiguous, unescaped |
| `ARRAY<BIGINT>` | `ARRAY<JSON>` — **not creatable** in 4.1.3 (`ARRAY unsupported sub-type: json`) |
| `ARRAY<LARGEINT>` (→ DECIMAL(38,0), fail-loud out of range) | `ARRAY<MAP/STRUCT/VARIANT>` — separate wire work |
| `ARRAY<DECIMAL(p,s)>` (p≤38) | |
| `ARRAY<BOOLEAN>` | |
| `ARRAY<DATE>` | |
| `ARRAY<DATETIME(0..6)>` | |
| `ARRAY<IPV4>`, `ARRAY<IPV6>` (if mapped to IPADDRESS/VARCHAR) | |
| `ARRAY<ARRAY<...>>` nesting of the above (≥3 levels proven) | nested arrays of a **denied** leaf type inherit the denial |

`ARRAY<FLOAT>` / `ARRAY<DOUBLE>`: **GO with a documented boundary caveat** — see
finding F3 (Doris renders `DOUBLE` max as a lossy 16-sig-digit form that reparses
to `Infinity` in Java). Approximate types already disable exact predicate
pushdown (PLAN §5), so this is acceptable if the connector surfaces the
overflow as `Infinity`/`-Infinity` rather than silently wrong finite values.

### Two-sentence escaping rule

Inside a Doris array, numbers/booleans are bare tokens (booleans as `1`/`0`),
`null` elements are the bare lowercase token `null`, `[]` is an empty array and a
whole-NULL array is SQL NULL (`getString` → `null`), with element separator
**comma-then-space** (`, `); string/date/datetime/IP elements are wrapped in
double quotes **but the quoted content is emitted with NO escaping whatsoever** —
an embedded `"` is a bare `"`, a backslash is a bare `\`, and a comma / bracket /
newline / tab / Unicode byte appears literally, byte-for-byte, exactly as in the
scalar value.

---

## 2. Observed wire grammar (EBNF-ish, Doris 4.1.3, JDBC `getString()`)

```
array        = "[" [ element *( ", " element ) ] "]"
element      = null-token | bare-scalar | quoted-scalar | array
null-token   = "null"                          ; lowercase, byte-verified over JDBC
separator    = "," SP                          ; comma + single space, always

bare-scalar  = int | largeint | decimal | float | boolean
  int        = ["-"] DIGIT+                     ; TINYINT/SMALLINT/INT/BIGINT
  largeint   = ["-"] DIGIT+                     ; full 128-bit, decode as BigInteger
  decimal    = ["-"] DIGIT+ "." DIGIT+          ; trailing zeros + scale PRESERVED
  float      = mantissa [ ("e"|"E") ["+"|"-"] DIGIT+ ]   ; see F3 for boundary form
  boolean    = "1" | "0"                        ; NOT true/false

quoted-scalar = DQUOTE <raw bytes, NO escaping> DQUOTE
  ; used for DATE, DATETIME, VARCHAR/CHAR/STRING, IPV4, IPV6
  ; DATE      => "uuuu-MM-dd"
  ; DATETIME  => "uuuu-MM-dd HH:mm:ss" [ "." fraction ]   (fraction width = declared scale)
  ; IPV4/IPV6 => canonical text (never contains DQUOTE / "," / "[" / "]")
  ; STRING    => arbitrary bytes, INCLUDING DQUOTE / "," / "[" / "]" / \ / NL / TAB
```

`SP` = one 0x20 byte. `DQUOTE` = one 0x22 byte. There is **no type tag/header**
on the wire; element type must come from `information_schema.columns.COLUMN_TYPE`
(see the protocol probe report). Empty array = `[]` (hex `5B5D`); NULL array =
JDBC `getString` returns Java `null`; NULL element = bare `null` inside the
brackets. These three are cleanly distinguishable (finding F1).

### Escaping rules — the decisive evidence (byte level)

`p0_array_probe0.t`, ARRAY<VARCHAR> element vs the same scalar VARCHAR, hex bytes:

| element value | scalar `getString` hex | ARRAY `getString` hex | note |
|---|---|---|---|
| `has"dq` | `68 61 73 22 64 71` | `5B 22 68617322 6471 22 5D` = `["has"dq"]` | inner `"` is bare `22`, **not** `\"` |
| `back\slash` | `...5C...` | `["back\slash"]` (single `5C`) | backslash **not** doubled |
| `a,b` | `61 2C 62` | `["a,b"]` | comma literal inside quotes |
| `newline<0A>here` | `...0A...` | `["newline<0A>here"]` | **real newline**, not `\n` |
| `tab<09>here` | `...09...` | `["tab<09>here"]` | **real tab**, not `\t` |

Conclusion: Doris array text is a *display* rendering with quotes but **no escape
layer**. This is the root cause of the string ambiguity.

> Note on the prior probe report: the `\"`/`\\` shown in
> `probe-raw-output-4.1.3.txt` for array string elements were introduced by that
> probe's own `repr()` printer, not by Doris. The raw JDBC bytes (above) carry no
> escaping. This spike corrects that reading.

---

## 3. Fixture → wire-text → decoded → oracle (verbatim wire text)

Full dump in `evidence/array-spike-raw-output.txt` (PART C/D). Representative rows
(wire text is exactly what `getString()` returned):

| fixture | wire text (verbatim) | decoded | oracle | ok |
|---|---|---|---|---|
| num.a_int id=4 | `[1, null, 3]` | `[1, NULL, 3]` | size 3, elems `1`,∅,`3` | ✓ |
| num.a_int id=5 | `[]` | `[]` | size 0 | ✓ |
| num.a_int id=6 | *(SQL NULL)* | NULL array | array_size NULL | ✓ |
| num.a_largeint id=3 | `[170141183460469231731687303715884105727]` | BigInteger 2¹²⁷−1 | exact | ✓ |
| num.a_largeint id=2 | `[-170141183460469231731687303715884105727]` | BigInteger −(2¹²⁷−1) | exact | ✓ |
| num.a_dec92 id=1 | `[1.10]` | BigDecimal `1.10` (scale 2) | `1.10` | ✓ |
| num.a_dec3810 id=1 | `[1.0000000000]` | BigDecimal scale 10 | `1.0000000000` | ✓ |
| num.a_boolean id=4 | `[null, 1, 0]` | `[∅, true, false]` | size 3 | ✓ |
| num.a_date id=2 | `["0000-01-01"]` | LocalDate 0000-01-01 | `0000-01-01` | ✓ |
| num.a_dt6 id=1 | `["2021-06-15 12:34:56.789012"]` | LocalDateTime µs | micros kept | ✓ |
| num.a_dt6 id=2 | `["0000-01-01 00:00:00.000000"]` | LocalDateTime | `.000000` kept | ✓ |
| num.a_float id=3 | `[3.402823e+38]` | float 3.402823E38 | value match | ✓ |
| num.a_double id=3 | `[1.797693134862316e+308]` | **Infinity** (F3) | oracle also Infinity | ✓* |
| nested.a2 id=2 | `[[1, null], null, []]` | `[[1,∅], ∅, []]` | matches | ✓ |
| nested.a3 id=1 | `[[[1, 2], [3]], [[4]]]` | 3-level nested | matches | ✓ |
| iparr.a_ipv4 id=1 | `["192.168.1.1", "0.0.0.0", "255.255.255.255"]` | 3 canonical IPs | matches | ✓ |
| iparr.a_ipv6 id=1 | `["2001:db8::ff00:42:8329", "::", "fe80::1"]` | 3 canonical IPs | matches | ✓ |
| strs.a_vc id=1 | `["has,comma"]` | 1 elem `has,comma` | array_size 1 | ✓ (lucky) |
| strs.a_vc id=17 | `["[1, 2]"]` | 1 elem `[1, 2]` | array_size 1 | ✓ (lucky) |
| **strs.a_vc id=18** | `["a", "b"]` | **2 elems `a`,`b`** | **array_size 1** (`a", "b`) | **✗ silent** |
| **strs.a_vc id=31** | `["x", "y", "real2"]` | **3 elems** | **array_size 2** (`x", "y`,`real2`) | **✗ silent** |
| **strs.a_vc id=33** | `["trailing", "]` | **decoder throws** | array_size 1 (`trailing", `) | **✗ throw** |
| longstr.a_str id=1 | `["AAAA…70000…"]` | 1 elem, 70000 chars | array_size 1 | ✓ |

`✓*` = agrees with oracle but both are the F3 boundary artifact (`Infinity`).

**Totals: 179 pass / 0 unexpected fail / 4 string-ambiguity findings** across the
num/nested/iparr/strs/longstr fixtures (numeric, temporal, boolean, nested,
IP, and non-hazard string cells all round-trip exactly).

---

## 4. Ambiguity verdict, per element type

| element type | wire shape | ambiguous? | why |
|---|---|---|---|
| TINYINT/SMALLINT/INT/BIGINT | bare `-?\d+` | **No** | alphabet ∩ {`, ` `[` `]` `"`} = ∅ |
| LARGEINT | bare `-?\d+` | **No** | same; decode BigInteger |
| DECIMAL(p,s) | bare `-?\d+\.\d+` | **No** | same |
| BOOLEAN | bare `1`/`0` | **No** | same |
| FLOAT | bare `…e±NN` | **No** (value-exact) | boundary render caveat F3 |
| DOUBLE | bare `…e±NNN` | **No** (structurally) | F3: max reparses to Infinity |
| DATE | `"uuuu-MM-dd"` | **No** | value alphabet excludes `"`,`,`,`[`,`]` |
| DATETIME(p) | `"… …"` | **No** | same |
| IPV4 / IPV6 | `"canonical"` | **No** | same |
| **VARCHAR/CHAR/STRING** | `"raw, unescaped"` | **YES** | value may contain `"`, `, `, `[`, `]` verbatim |
| ARRAY&lt;allowed&gt; | nested `[...]` | **No** | recursion over an unambiguous leaf |
| ARRAY&lt;string&gt; | nested | **YES** | inherits the leaf ambiguity |

### The string ambiguity, precisely (proven, byte-identical)

`p0_array_probe0.amb` — two semantically different arrays, one wire text:

```
id=1  array_size=1  ["a", "b"]  hex 5B2261222C202262225D   -- ONE element, value  a", "b
id=2  array_size=2  ["a", "b"]  hex 5B2261222C202262225D   -- TWO elements  a , b
```

The wire bytes are identical; only the `array_size()` oracle separates them. A
`getString()`-only decoder therefore cannot recover the correct array for a
`VARCHAR` element that contains the substring `", ` (or a leading/trailing `"`,
or an embedded `]`). Because Doris performs no escaping, **there is no in-band
signal** to disambiguate, and the failure modes are all three of: silently wrong
element count/values (id=18, id=31), lucky-correct (id=1, id=2, id=17, id=30,
id=32 — non-deterministic w.r.t. value content), and hard parse error (id=33).
"Sometimes silently wrong" is disqualifying under AGENTS.md ("prefer failing loud
over silently wrong") — so string-family element types cannot be native in v1.

---

## 5. Malformed-input behavior (decoder must fail loud)

The spike decoder has **no permissive fallback**; every malformed sample throws
`DecodeException` with a wire offset (or a typed parse exception). Battery result
(PART D of the raw output):

| input | element kind | outcome |
|---|---|---|
| `[1, 2` (truncated, no `]`) | INT | throw: unterminated array @5 |
| `1, 2]` (no leading `[`) | INT | throw: expected `[` @0 |
| `[1,2]` (`,` without space) | INT | throw: separator `,` not followed by space @2 |
| `[1, 2] ` (trailing garbage) | INT | throw: trailing garbage @6 |
| `[1, , 3]` (empty element) | INT | throw: empty bare token @4 |
| `[abc]` (non-numeric) | INT | throw: malformed INT token @1 |
| `[999]` (overflow) | TINYINT | throw: TINYINT out of range @1 |
| `[2, true]` | BOOLEAN | throw: BOOLEAN not 1/0 @1 |
| `["2021-13-99"]` | DATE | throw: DateTimeParseException (month 13) |
| `["unterminated` | STRING | throw: unterminated quoted element @1 |
| `["a"x, "b"]` (interior `"` in DATE alphabet) | DATE | throw: alphabet violation @3 |
| `[null` (truncated after null) | INT | throw: unterminated array @5 |

All 12 fail loud. The decoder also fails loud on the one string case where its
heuristic mis-detects a close (id=33), which is the *desirable* half of the
string ambiguity; the *undesirable* half (id=18/31 silent) is exactly why
strings are excluded.

---

## 6. Extra probes (task item 6)

- **`getObject()` class for an ARRAY column** = `java.lang.String` (value `[1]`).
  RSMD reports `columnClassName=java.lang.String`, `typeName=CHAR`, `type=1`.
  Connector/J does **not** implement `java.sql.Array` for Doris arrays — confirms
  PLAN §5 (read via `getString`, build Trino Blocks with an `ObjectReadFunction`).
- **`ARRAY<DATETIME(6)>` keeps microseconds**: `["2021-06-15 12:34:56.789012"]`
  and even the zero fraction is retained: `["0000-01-01 00:00:00.000000"]`.
- **`ARRAY<DECIMAL>` keeps trailing zeros/scale**: `[1.10]`, `[1.0000000000]`.
  Decode with `BigDecimal` (no double round-trip) to preserve scale exactly.
- **All-NULL untyped array**: `SELECT ARRAY(NULL, NULL)` → `getString` =
  `[null, null]`, RSMD `typeName=CHAR`. Over JDBC the null token is **lowercase**
  `null` for both stored columns and literal expressions (the uppercase `NULL`
  seen in the mysql CLI is a CLI display path, not the JDBC wire).
- **empty vs null-element vs null-array** are byte-distinct: `[]` (`5B5D`) vs
  `[1, null, 3]` (contains `6E756C6C`) vs SQL NULL (`getString` → Java `null`).

---

## 7. Consequences for the connector (P1/P2)

1. **Type resolution** from `information_schema.columns.COLUMN_TYPE`, never
   `getColumns` (already established in the protocol probe). The array element
   type string (`array<int(11)>`, `array<varchar(200)>`, `array<array<int>>`)
   drives which allowlist branch the decoder uses.
2. **Read path**: `getString()` → strict recursive-descent decode →
   `ObjectReadFunction` → Trino Block. Reuse only PostgreSQL's Block-construction
   pattern; there is no `java.sql.Array`.
3. **Element allowlist enforced in the type mapper.** An `ARRAY` whose (possibly
   nested) leaf element is VARCHAR/CHAR/STRING/JSON/MAP/STRUCT/VARIANT follows the
   configured unsupported-type policy (unsupported column, or VARCHAR of the whole
   array text) — it must **not** be exposed as a native `ARRAY<VARCHAR>` because
   the decode is provably ambiguous.
4. **LARGEINT array elements**: decode as `BigInteger`; map to Trino
   `DECIMAL(38,0)` and **fail loud** on the out-of-DECIMAL(38,0) extreme (the
   `±(2¹²⁷−1)` boundary values round-trip through BigInteger but exceed
   DECIMAL(38,0)); never clamp. Matches PLAN §5/§11.
5. **FLOAT/DOUBLE array elements** (F3): parse strictly; surface Doris's
   `Infinity`/`-Infinity`/`NaN` faithfully. Do not enable exact-equality predicate
   pushdown over array float elements. The DOUBLE-max lossy-render → Java Infinity
   is a Doris text artifact; document it and do not paper over it.
6. **The `contains`/`arrays_overlap` value proposition survives**: those predicates
   operate on `ARRAY<INT/BIGINT/...>` and `ARRAY<VARCHAR>`. Numeric array indexes
   are fully supported natively. For **string** array index acceleration, v1 must
   either (a) expose the array as VARCHAR and push a *string-literal-parameterized*
   `array_contains` as a superset pre-filter with the exact Trino predicate
   retained (needs its own live test), or (b) defer string-array predicates. That
   design choice is a P2 item, flagged here — it is **not** solved by native
   `ARRAY<VARCHAR>` decoding, which is unsafe.

---

## 8. Findings summary

- **F1** — empty `[]`, NULL element (`null`), and NULL array (`getString`→null)
  are byte-distinct and cleanly decodable. ✅
- **F2** — numeric/largeint/decimal/boolean/date/datetime/ipv4/ipv6 array text is
  unambiguous; exact round-trip incl. LARGEINT 128-bit, DECIMAL scale, DATETIME
  microseconds, ≥3-level nesting. ✅
- **F3** — DOUBLE `Double.MAX_VALUE` renders on the wire as
  `1.797693134862316e+308` (16 sig digits) which **reparses to `Infinity`** in
  Java; FLOAT max is exact. Boundary caveat for approximate types. ⚠️
- **F4 (decisive NO-GO)** — Doris emits quoted string array elements with **no
  escaping**; a `VARCHAR` value containing `", ` / leading-or-trailing `"` / `]`
  is byte-indistinguishable from the delimiter grammar, producing silently-wrong
  or throwing decodes. Proven byte-identical (`p0_array_probe0.amb`). ❌ for
  ARRAY<VARCHAR/CHAR/STRING>.
- **F5** — `ARRAY<JSON>` is **not creatable** in Doris 4.1.3
  (`ARRAY unsupported sub-type: json`); `ARRAY<IPV4>`/`ARRAY<IPV6>` are creatable
  and unambiguous. 
- **F6** — decoder fails loud on all 12 malformed samples with wire-offset info;
  no permissive fallback. ✅

---

## 9. Reproduce

```sh
# cluster already running: trino-doris-fe, mysql 127.0.0.1:9130 (stock 4.1.3)
# throwaway spike (kept out of the repo) lives under /tmp/opencode/array-spike:
#   fixtures.sql               -- p0_array_spike DDL/DML (all element types + hazards)
#   ArrayWireDecoder.java      -- strict recursive-descent decoder (== the checked-in evidence)
#   Harness.java               -- oracle capture + decoder-vs-oracle diff + malformed battery
#   BytesProbe.java WireDump.java -- byte-level escaping proof + verbatim wire dump
# build: javac -cp mysql-connector-j-9.7.0.jar ArrayWireDecoder.java Harness.java
# run:   java  -cp .:mysql-connector-j-9.7.0.jar Harness
```

The `p0_array_spike` / `p0_array_probe0` / `p0_nest_probe` / `p0_exotic_probe`
databases are left in place on the running cluster for re-verification. The spike
decoder is checked in as `evidence/ArrayWireDecoder-spike.java` (non-production,
throwaway; header marks it as such).
