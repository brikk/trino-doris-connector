# LEDGER — P0 type & read-only capability contract (`trino-doris` v1)

**Status: DECISION-GRADE. This is the authoritative contract P1/P2 code against.**
No hedging: every ruling below is either PROVEN (with a cited report + finding) or
explicitly PARKED/OPEN. Where a ruling refines or contradicts the PLAN, it is
recorded here and cross-listed in section G (the PLAN is *not* edited).

**Baseline:** Apache Doris `4.1.3-rc02-7126cf65d96` (FE+BE), MySQL protocol string
`5.7.99`, MySQL Connector/J `9.7.0`, Trino `483`. Any Doris version change voids
this ledger and requires a re-probe.

**Evidence sources (cited by short-name):**
- **PROBE** = `REPORT-protocol-type-probe-doris-4.1.3.md`
- **ARRAY** = `REPORT-array-wire-decoder-spike.md`
- **STOCK** = `REPORT-stock-trino-mysql-vs-doris-4.1.3.md`
- **ASM**   = `REPORT-plugin-assembly-proof.md`
- **SR**    = `LEDGER-starrocks-trino-connector-audit.md`
- **BRIKK** = `REPORT-brikk-metadata-api-audit.md`
- **PLAN**  = `../trino-ducklake/dev-docs/PLAN-trino-doris-extension.md`

**Two hard global rules driving every mapping (AGENTS.md):**
1. **Fail loud over silently wrong** — a guard that throws is preferred to a row
   that over/under-returns. Stock's silent-IGNORE and silent-scale-loss are the
   anti-pattern this connector exists to fix (STOCK "Type-surface highlights").
2. **Metadata truth = `information_schema.columns.COLUMN_TYPE`, NOT
   `DatabaseMetaData.getColumns`.** `getColumns` is proven lossy (PROBE §1,
   Implication #1) — it narrows LARGEINT→`INT`, reports IPv4/IPv6/VARIANT as
   `OTHER`, BITMAP as `BIT`, and for ARRAY reports only the *element* type. Every
   type decision keys off `COLUMN_TYPE`.

---

## A. Type contract v1 (final)

Read strategy legend:
- **is.COLUMN_TYPE** = resolve the Doris type from
  `information_schema.columns.COLUMN_TYPE` (never `getColumns`).
- **getX** = the JDBC getter used for values (`getInt`, `getLong`, `getString`,
  `getBigDecimal`, `getObject(LocalDateTime.class)`, `getBoolean`).
- PPD = predicate-pushdown eligibility for v1.

| Doris type | Trino type v1 | Value read strategy | PPD v1 | Deviation from PLAN §5 (evidence) |
|---|---|---|---|---|
| `boolean` (`tinyint(1)`) | `BOOLEAN` | discriminate `boolean` vs `tinyint` via **is.COLUMN_TYPE**; value `getBoolean`/`getInt` under `tinyInt1isBit=false` | domain (=, IS NULL) | Refines §5: protocol cannot tell BOOLEAN from TINYINT; discrimination MUST come from is.COLUMN_TYPE. `getColumns` reports both as TINYINT (PROBE §2,§4; STOCK row `boolean`). |
| `tinyint` / `smallint` / `int` | `TINYINT`/`SMALLINT`/`INTEGER` | `getInt` | domain | none — signed, exact min/max (PROBE §3 scalars). |
| `bigint` | `BIGINT` | `getLong` | domain | none — exact `±9223372036854775807` (PROBE §3). |
| `largeint` (128-bit signed) | `DECIMAL(38,0)` | **`getString` → parse to BigInteger**, then check range, else **fail loud** | domain, **only** for literals/domains representable in DECIMAL(38,0) | Refines §5: LARGEINT arrives as **`String`** (RSMD `CHAR`), NOT numeric. `getBigDecimal(LARGEINT)` **throws `SQLDataException: Invalid integer format` on the negative extreme** `-(2^127-1)` — do NOT use it; parse `getString`. Fail loud out of DECIMAL(38,0) range; never clamp. (PROBE §2,§3,Impl #2). Stock's `DECIMAL(20,0)` overflows on ordinary values — rejected (STOCK; SR R3). |
| `float` | `REAL` | `getFloat` | **DENIED** (approximate) | none — `NaN` reads fine as `Float.NaN`; `±3.402823E38` round-trip (PROBE §3). Exact-equality PPD disabled (SR K8). |
| `double` | `DOUBLE` | **`getString` then parse**, handle `Infinity`/`-Infinity`/`NaN`; do NOT rely on `getDouble` | **DENIED** (approximate) | Refines §5: **`getObject`/`getDouble` THROW `SQLDataException: Value '∞' is outside of valid range` on `Infinity`/`-Infinity`**; `getString` returns `"Infinity"`/`"-Infinity"`. Read via getString/fail-loud per row, never a blanket query error (PROBE §3,Impl #6; STOCK "one poisoned row kills the scan"). |
| `decimalv3(p,s)`, p≤38 | `DECIMAL(p,s)` | `getBigDecimal` | domain | none — exact precision/scale (PROBE §3). |
| `decimalv3(p,s)`, 38<p≤76 (Decimal256) | **VARCHAR** (or fail-loud per unsupported-type policy) | `getString` (exact) | none | Refines §5: the **read path IS exact** for Decimal256 via getString/getBigDecimal/getObject — the limit is Trino's public DECIMAL ceiling of 38, **not** the wire/driver. DDL needs `enable_decimal256=true`. Stock's `MAP_TO_NUMBER` surrogate **silently loses scale** (`...890.1234567890`→`...890.123456789`) — rejected (PROBE §3,Impl #3; STOCK). |
| `date` | `DATE` | `getObject(LocalDate)` / `getString`; handle `0000-01-01` | domain | Refines §5: **`getDate`/`getObject(java.sql.Date)` THROW `SQLException: YEAR` on `0000-01-01`**; read via getString/LocalDate. `9999-12-31` OK (PROBE §3,Impl #6). |
| `datetime(p)` (p=0/3/6) | `TIMESTAMP(p)`, max proven **p=6 (microseconds)** | **`getObject(LocalDateTime.class)`** (zoneless); **NEVER `getTimestamp`** | domain | Refines §5: `getTimestamp` **shifts the instant by the session zone** and **throws `YEAR` on `0000-*`**; `getObject(LocalDateTime)` is zoneless and tolerates `0000-*`. Doris DATETIME is zoneless as assumed. Max precision = 6 (µs); **nanosecond Trino timestamps must NOT push to µs Doris** (PROBE §3,§6,Impl #5; PLAN §6.2 deny). |
| `datetime`+zone / TIMESTAMPTZ | **deferred** | — | — | Not probed as a distinct type on 4.1.3; PLAN §5 version-gated. Do not expose in v1 until a dedicated DST/offset probe. |
| `char(n)` / `varchar(n)` / `string`(`text`) | `CHAR(n)`/`VARCHAR(n)`/`VARCHAR` (unbounded) | `getString` | **NULL-domain only** (G5) | Refines §5: string range/inequality/LIKE/equality PPD **disabled** until collation proven. Stock pushes `varchar =` to Doris AND re-checks locally — the exact G5 collation hazard; a remote equality is at most a superset pre-filter with the Trino predicate retained (STOCK pushdown ledger row b; PLAN G5). |
| `json` | `JSON` | `getString` → strict `jsonParse` | **DENIED** (blind); specific proven rules later | none — server-normalized text round-trips (PROBE §3; SR K14 `DISABLE_PUSHDOWN`). |
| `array<T>` (T in allowlist) | `ARRAY<T>` | `getString` → **strict recursive-descent decoder** → `ObjectReadFunction` → Trino Block | `contains`/`arrays_overlap`/`array_position` rules (P2) | See **ARRAY element allowlist verdict** below. Connector/J does **not** implement `java.sql.Array`; `getObject` returns `String` (ARRAY §6; PLAN §5). |
| `ipv4` / `ipv6` | `IPADDRESS` if parser accepts Doris canonical text, else `VARCHAR` | `getString` | domain (as VARCHAR/IPADDRESS eq) | Refines §5: read as **canonical text** (`192.168.1.1`, `2001:db8::ff00:42:8329`, `::`, `fe80::1`). `getColumns` reports `OTHER`; must use is.COLUMN_TYPE. Stock **silently drops** these (PROBE §3,Impl #4; STOCK). |
| `variant` | `JSON` (or VARCHAR) | `getString` | **DENIED** | none — normalized-JSON text; RSMD even labels it `JSON`. Stock silently drops (PROBE §3; STOCK). |
| `map<K,V>` | **deferred** (unsupported or VARCHAR-of-text) | `getString` | — | Wire grammar observed (`{"k":v, ...}`, keys quoted, values by type-rule, empty `{}`, NULL=SQL NULL) but **native mapping deferred** — separate wire work (PROBE §"wire-format"; ARRAY allowlist). |
| `struct<...>` | **deferred** (unsupported or VARCHAR-of-text) | `getString` | — | Wire grammar observed (`{"a":10, "b":"hello"}`, field-name keyed, NULL fields `null`) but deferred (PROBE §"wire-format"). `getColumns` reports garbage `INT` (PROBE §1). |
| `bitmap` / `hll` / `quantile_state` / `agg_state` | **HIDDEN / unsupported** | n/a | — | none — opaque engine states. BITMAP/HLL select as **NULL text**; AGG_STATE returns **raw binary state bytes**; only usable via Doris fns (`bitmap_to_string`, `hll_cardinality`). Hide as row columns (PROBE §"BITMAP/HLL/AGG_STATE",Impl #9). |

### ARRAY element allowlist verdict (VERDICT: GO-WITH-RESTRICTIONS — ARRAY §1)

The v1 native `ARRAY<T>` allowlist. Decoder = strict recursive-descent over
`getString`, fail-loud on malformed (ARRAY §5: 12/12 malformed samples throw with
wire offset; no permissive fallback).

**ALLOWED (unambiguous wire grammar — ARRAY §4):**
- `ARRAY<TINYINT|SMALLINT|INT|BIGINT>` — bare `-?\d+`.
- `ARRAY<LARGEINT>` — bare `-?\d+`, decode BigInteger → **DECIMAL(38,0), fail loud
  out of range** (`±(2^127-1)` round-trips through BigInteger but exceeds
  DECIMAL(38,0)); never clamp (ARRAY §3 largeint rows, §7.4).
- `ARRAY<DECIMAL(p,s)>` p≤38 — bare, **decode with BigDecimal (no double
  round-trip); trailing zeros/scale preserved** (`[1.10]`, `[1.0000000000]`)
  (ARRAY §6, F2).
- `ARRAY<BOOLEAN>` — bare `1`/`0` (NOT `true`/`false`) (ARRAY §2).
- `ARRAY<DATE>` — quoted `"uuuu-MM-dd"`.
- `ARRAY<DATETIME(0..6)>` — quoted; **microseconds preserved**, incl. zero
  fraction `["...00:00:00.000000"]` (ARRAY §6, F2).
- `ARRAY<IPV4>`, `ARRAY<IPV6>` — quoted canonical text (value alphabet excludes
  `"`,`,`,`[`,`]`) — only if IP type is mapped (ARRAY §4).
- `ARRAY<ARRAY<...>>` nesting of the above — **≥3 levels proven** (`[[[1,2],[3]],[[4]]]`)
  (ARRAY §3 nested rows, F2).

**DENIED in v1:**
- **`ARRAY<VARCHAR|CHAR|STRING>` — HARD NO-GO (decisive, F4).** Doris emits string
  array elements with **ZERO escaping**: an embedded `"` is bare `0x22`, a
  backslash is bare `0x5C`, and commas/brackets/newlines/tabs appear literally
  byte-for-byte. Two semantically different arrays produce **byte-identical wire
  text**: `p0_array_probe0.amb` id=1 (one element value `a", "b`) and id=2 (two
  elements `a`,`b`) both serialize to hex `5B2261222C202262225D`; only the
  `array_size()` oracle separates them. A `getString`-only decoder therefore
  **cannot** recover the correct array — failure modes are silently-wrong element
  count (ARRAY §3 id=18, id=31), lucky-correct, and hard-throw (id=33). "Sometimes
  silently wrong" is disqualifying under AGENTS.md (ARRAY §4, F4).
  - **String-array ambiguity evidence:** ARRAY §4 "The string ambiguity,
    precisely" + §2 byte table.
- **`ARRAY<JSON>` — not creatable** in Doris 4.1.3 (`ARRAY unsupported sub-type:
  json`) (ARRAY F5).
- **`ARRAY<MAP|STRUCT|VARIANT>`** — separate wire work, deferred.
- **Nested arrays whose leaf is a denied type inherit the denial** (ARRAY §1,§4).

**Enforcement:** the element allowlist is enforced in the type mapper; an ARRAY
whose (possibly nested) leaf is a denied type follows the configured
unsupported-type policy (unsupported column, or VARCHAR-of-whole-array-text) — it
**must not** be exposed as native `ARRAY<VARCHAR>` (ARRAY §7.3).

**FLOAT/DOUBLE array caveat (GO-with-caveat, F3):**
- `ARRAY<FLOAT>` — **GO**, FLOAT max exact.
- `ARRAY<DOUBLE>` — **GO with documented boundary caveat**: Doris renders
  `Double.MAX_VALUE` on the wire as `1.797693134862316e+308` (16 sig digits) which
  **reparses to `Infinity` in Java**. Surface as `Infinity`/`-Infinity`/`NaN`
  faithfully; do NOT paper over with a silently-wrong finite value; do NOT enable
  exact-equality PPD over float array elements (ARRAY §1,§4,§7.5, F3). This is the
  array-side twin of the scalar **DOUBLE Infinity getter failure** (PROBE §3:
  scalar `getObject`/`getDouble` throw on `Infinity`).

**`contains`/`arrays_overlap` value proposition survives** for numeric arrays
natively. **String-array index acceleration is a P2 design item, NOT solved by
native `ARRAY<VARCHAR>`** — either (a) expose as VARCHAR and push a
string-literal-parameterized `array_contains` as a *superset pre-filter with the
exact Trino predicate retained* (needs its own live test), or (b) defer (ARRAY §7.6).

### DECIMAL(76) / Decimal256 stance (final)

**Map to VARCHAR (or fail-loud), NOT native DECIMAL.** Rationale: the read path is
**wire-exact** (PROBE Impl #3) — the sole constraint is Trino's public DECIMAL
contract stopping at precision 38 (PLAN §5 note; PROBE Impl #3). `Int128` backs
long DECIMAL internally but does not create a connector-visible 128-bit-precision
type. This is a **Trino-type-ceiling** limitation, explicitly **not** a wire/driver
one — the PLAN's pessimistic "unsupported or VARCHAR" wording is correct in outcome
but its implied reason (wire) is wrong (see §G).

### LARGEINT string-read + fail-loud rule (final)

Read `getString` → parse BigInteger → if outside `DECIMAL(38,0)` **throw**
(`DorisConnectorException`, engine-skip classified), never clamp/truncate. Do NOT
use `getBigDecimal` (throws on negative extreme). Numeric PPD allowed only for
literals/domains representable in DECIMAL(38,0) so the unsupported extreme is
explicit (PROBE Impl #2; PLAN §5/§11).

### DATETIME read-via-LocalDateTime rule (final)

Read via `getObject(LocalDateTime.class)` (or `getString`), **never
`getTimestamp`** — `getTimestamp` applies the client calendar and shifts the
instant by the session zone (PROBE §6: same value shows `12:34:56`→`16:34:56` in
NY, `04:34:56` in Shanghai) and throws `YEAR` on `0000-*`. Max precision 6 (µs).

---

## B. Connection property pin list (Connector/J URL, v1)

Each property with its evidence. These are the exact properties `DorisJdbcConfig` /
`DriverConnectionFactory` set.

| Property | Value | Why / evidence |
|---|---|---|
| `tinyInt1isBit` | **`false`** | Mandatory. Default (`true`) reports **every Doris TINYINT as `BIT`/`Boolean`**. With `false`, BOOLEAN & TINYINT both surface as `TINYINT`/`Integer`; discriminate via is.COLUMN_TYPE (PROBE §4,Impl #7; SR K2; PLAN §4.3). |
| `characterEncoding` | **`UTF-8`** | Unicode/CJK/emoji round-trip proven (PROBE §3 CHAR/VARCHAR; SR K2 `useUnicode=true,characterEncoding=utf8`; PLAN §4.3). |
| streaming | **`setFetchSize(Integer.MIN_VALUE)`** (Connector/J row streaming; == `MySqlClient.enableStreamingResults()`) | Proven ~**2.4× lower peak heap** (56 MB vs 134 MB) on a 1.001M-row scan under `-Xmx256m`, no OOM. `useCursorFetch=true`+`setFetchSize(1000)` also works but **no memory win** and adds a per-block round-trip — rejected (PROBE §7,Verdicts; PLAN G8). |
| `useServerPrepStmts` | **`false` (client emulation) — v1 default** | Both client and server prep executed and matched **identically** for INT/BIGINT/DECIMAL/DATE/DATETIME/VARCHAR/BOOLEAN `?`-bound predicates; **no type-fidelity difference**. Server prep is functional on 4.1.3, but client emulation is the safer default until a broader parameter-type sweep is done (PROBE §5). |
| `connectTimeout` / socket timeout | set explicitly (finite) | PLAN §4.3; SR K2 (`connectTimeout`). Exact values a P1 config decision. |
| TLS | passed through explicitly | PLAN §4.3 (out of P0 probe scope). |
| multi-host | **plain comma-list** `jdbc:mysql://h1:p,h2:p/db` | Comma-list **connects** (proven single-FE, `SELECT 1`=1). The **`sequential:(host=..,port=..)` form is REJECTED** by Connector/J 9.7.0 (`WrongArgumentException`). Use comma-list or a TCP load balancer. **Caveat: only single-FE failover-syntax proven; true multi-FE follower/observer failover NOT tested — see §F NOT-DONE** (PROBE §11; PLAN §4.3). |
| URL must not contain a database/catalog path | enforced (`isUrlWithoutDatabase` guard) | Catalog-as-schema (Doris db → Trino schema) requires connecting without a default DB (SR K3; PLAN G9/§4.4). |
| session vars | applied at **session scope only, never global** | `query_timeout`, `exec_mem_limit`, `time_zone` all settable per-session via `SET` or URL `sessionVariables=` (PROBE §9; PLAN §4.3). |

**Explicitly NOT set (rejected from StarRocks prior art):**
- `rewriteBatchedStatements=true` — a **write** optimization (SR K2/R1); read-only.
- MariaDB driver + `mysql`→`mariadb` URL string rewrite — pin **MySQL Connector/J
  9.7.0** (BOM-aligned, ASM §7); the naive `replace("mysql","mariadb")` is unsafe
  (SR R5).

---

## C. Read-only capability matrix

Operations v1 **must deny** (PLAN §7), cross-referenced with the concrete stock
write-danger evidence. **Motivating fact: stock Trino's MySQL connector does NOT
refuse writes — it executed a `DELETE` that permanently destroyed a row**
(STOCK "Write-path danger findings"):

> `DELETE FROM `p0_trino_smoke`.`t2` WHERE `id` = 1` → `State=OK`, table went
> `{1,2}`→`{2}`, Trino reported `DELETE: 1 row`.

| Operation | v1 verdict | Stock evidence (the danger v1 must close) |
|---|---|---|
| INSERT | **DENY** | Reached Doris; failed only on a sandbox replication default (`replication num is 3, available backend num is 1`) — i.e. a real INSERT was generated & sent (STOCK). |
| UPDATE | **DENY** | Reached Doris; rejected only by Doris's model check (`Only unique table could be updated`) — connector did not block it (STOCK). |
| DELETE / MERGE / TRUNCATE | **DENY** | **DELETE SUCCEEDED and deleted a row** (above). DUP-table DELETE also executed (0 rows only due to key model) (STOCK). |
| CREATE / CTAS / temp-table copy | **DENY** | CTAS `CREATE TABLE ... AS SELECT ... WHERE 0=1` reached Doris; explicit CREATE failed only on DDL rendering (Trino emitted MySQL `tinytext`), not a refusal (STOCK). |
| DROP / ALTER schema or table | **DENY** | DROP returned the success path (STOCK). |
| comments / property changes | **DENY** | PLAN §7 (StarRocks `setTableComment` is in the rejected write path, SR R1). |
| arbitrary procedures | **DENY** | PLAN §7. |
| arbitrary SQL passthrough / `query()` PTF | **DO NOT BIND** in v1 | StarRocks binds `query()` PTF by default (SR R8) — **must-not-port** (PLAN G7.4). SQL that returns no rows can still mutate Doris before the connector notices (PLAN G6). |

**Defense in depth required (all layers, PLAN G7):**
1. `ReadOnlyDorisClient extends ForwardingJdbcClient` rejects every mutating
   `JdbcClient` method.
2. `DorisReadOnlyAccessControl` denies write/DDL/procedure ops.
3. Do not bind the JDBC `query()` table function.
4. **`system.execute` audit obligation:** Base JDBC auto-installs a `system.execute`
   procedure; **prove it is denied before P1 completes**; if access control cannot
   make the denial airtight, **replace/fork the module binding** rather than ship a
   writable escape hatch (PLAN G7.5, §10 P1).
5. **SELECT-only fixture account requirement:** a Doris account with
   SELECT/SHOW/EXPLAIN-only privileges, tested so a connector regression **cannot**
   write (PLAN G7.1, §7, §10 P1). This is independent of connector code — the
   stock DELETE proves code-only enforcement is insufficient.

**Remains available (not write support):** `SHOW`, metadata reads, `EXPLAIN`,
`SELECT`, cancellation, harmless metadata-cache flush (PLAN §7).

---

## D. Cancellation / timeout contract (proven numbers)

All PROVEN against live 4.1.3 (PROBE §8; STOCK "Cancellation verdict"). The PLAN
§4.3 contract (`Statement.cancel()` + server `query_timeout`) is **proven; both
promptly release Doris work.**

| Mechanism | Proven behavior | Evidence |
|---|---|---|
| `Statement.cancel()` | `SELECT SLEEP(30)` cancelled from another thread; runner returned in **~209 ms** with `MySQLStatementCancelledException`. (Use `SLEEP` — a `COUNT(*)` self-join is answered by Doris count-pushdown in ~1 ms and is not a valid cancel target.) | PROBE §8 |
| `KILL QUERY <id>` (what `cancel()` issues under the hood) | Removes the query from `SHOW PROCESSLIST` in **~18 ms** | PROBE §8 (Appendix B) |
| `SET query_timeout` (session) | `query_timeout=3` aborted `SELECT SLEEP(30)` at **3002 ms** with `query timeout` | PROBE §8,§9 |
| End-to-end via Trino | `CALL system.runtime.kill_query(...)` → Trino query `FAILED`; live Doris scan disappeared from `information_schema.processlist` within **~1 s** | STOCK |
| Session var defaults | `query_timeout=900s`, `exec_mem_limit≈100 GB`, `time_zone=Etc/UTC`; all settable per-session | PROBE §9 |

Adopt StarRocks' abort-on-not-drained pattern (`connection.abort(directExecutor())`)
plus per-statement `setQueryTimeout`, but **re-home the timeout as a first-class
`doris.query-timeout` in `DorisSessionProperties`** — StarRocks sourced it from a
*write* session-properties class (SR K11/R2).

---

## E. Single-statement / snapshot posture (G2)

**PROVEN: one JDBC statement per scan; Doris distributes it internally.** Every data
scan reaching the Doris FE audit log was a **single remote statement**
(`Client=172.30.81.2`, one `Stmt=` per scan) — G2 holds with stock Base JDBC and
the custom connector inherits it (STOCK "Pushdown ledger", "Every data scan is a
single remote statement, confirming G2").

- A single statement gives **one Doris read snapshot**; multiple independently
  issued tablet statements could observe different table versions if a write
  commits between splits — why tablet-splitting is deferred and gated on an
  explicit snapshot/version contract (PLAN G2, §4.5).
- **Audit-log evidence:** the pushed remote SQL is verbatim-observable in
  `fe.audit.log` `Stmt=` (e.g. `SELECT `id`,`c_int` FROM `p0_probe`.`scalars`
  WHERE `c_int` > 1000`) — this is the observability substrate for correctness
  proofs (STOCK pushdown ledger).
- **One refinement to inherit:** stock issues a **companion `SELECT * FROM <table>`
  metadata probe** (`ReturnRows=1`) before each scan to read `ResultSetMetaData`.
  The custom connector should **drop that per-scan probe by driving types from
  `information_schema`** — the data path stays a single statement regardless
  (STOCK "Extra remote round-trips"; §G).
- Positive baselines inherited & to preserve: numeric/date domain pushdown,
  LIMIT/TopN/count/sum pushdown, streaming, cancellation propagation (STOCK).

---

## F. P0 exit checklist (PLAN §10, "P0 — Decisions and live spike")

| P0 item | Status | Artifact / note |
|---|---|---|
| Sign off G1–G12 | **DONE** (evidence assembled for sign-off) | G1 (base-JDBC/SingleStore shape) SR K1; G2 §E; G4 ARRAY allowlist §A; G8 streaming PROBE §7; G12 ASM. Decision gates are user sign-off, but every gate now has live evidence. |
| Audit released brikk metadata API; propose additive API if needed; ask before touching `brikk-house` | **CLOSED 2026-07-19 — `dev.brikk.house:brikk-sql-metadata-jvm:0.7.0` RELEASED on Maven Central** (sha256 693575c17a041a0370b44a233969350f95d27c9bb47b14ebbbd1c80d2d3b1de8): the §D proposal was implemented direction-oriented (`FunctionHazard.sourceName`/`targetName`), `HazardRegistry.lookup` unchanged. The connector pins the artifact, cross-checks every rule's evidence tuple at connector construction (fail-loud), and runs `TestDorisPushdownEvidenceDrift` (version + sha256 + tuple pins). Original blockers, for the record: | BRIKK. **Blocked, not deferrable.** Two blockers: (1) **no released (non-SNAPSHOT) artifact exists**; (2) even the on-disk `0.1.0-SNAPSHOT` predates and **omits** `HazardRegistry`/`FunctionHazard`/`GeneratedTrinoDorisHazards` (jar 2026-07-12; hazards JSON 2026-07-13). Also an API-shape gap: `FunctionHazard` drops the **target Doris function name** (comment-only in the generated source). **Proposed exactly (NOT implemented):** D.1 — add defaulted nullable `sourceName`/`targetName` to `FunctionHazard`, populated from the JSON `trino`/`doris` fields (pure data-carry, no new probing); D.2(a) — connector picks `json_unquote` explicitly for the sole multi-target key `json_extract_scalar`; D.3 — cut a **non-SNAPSHOT** `brikk-sql-metadata-jvm` release containing the hazard classes and pin it. **Requires user approval before any `brikk-house` edit/publish; do NOT copy the JSON/registry into this repo as a workaround.** |
| Verify `trino-base-jdbc` + Connector/J plugin assembly/classloader contents | **DONE** | ASM ("PROVEN CLEAN"). Plugin dir = jar + `runtimeClasspath` (dir of jars, not shaded). **Key trap proven:** `compileOnly` excludes only `trino-spi`; base-jdbc/plugin-toolkit/airlift-json pull `slice`+`jackson-annotations`+`opentelemetry-context` back at compile scope — an explicit `runtimeClasspath` exclusion block is REQUIRED (§6 recipe). Connector/J `9.7.0` BOM-aligned. |
| Audit StarRocks connector; check in keep/reject ledger (G11) | **DONE** | SR (`LEDGER-starrocks-trino-connector-audit.md`). 15 KEEP design ideas, 8 REJECT (write path, MariaDB rewrite, `DECIMAL(20,0)` LARGEINT, no ARRAY reader, default-bound `query()` PTF), Trino 418→483 API drift watchlist. Port no code. |
| Point stock MySQL/SingleStore-shaped code at Doris 4.1.3 FE | **DONE** | STOCK (stock Trino 483 MySQL connector vs live 4.1.3). |
| Run protocol/type probe (§9.1) | **DONE** | PROBE (11 sections + BITMAP/HLL/AGG_STATE + wire-format). |
| Throwaway ARRAY text-decoder spike over every §5 ARRAY fixture | **DONE** | ARRAY (GO-WITH-RESTRICTIONS; 179 pass / 0 unexpected fail / 4 string-ambiguity findings; strict decoder checked in as evidence). |
| Confirm single-statement snapshot, streaming, cancellation | **DONE** | §E (G2), §D (cancel/timeout), PROBE §7 (streaming). |
| Confirm **FE failover** | **NOT-DONE (open P1+ obligation)** | Only the **multi-host URL *syntax*** is proven, on a **single-FE cluster** (comma-list connects; `sequential:(...)` rejected — PROBE §11). True **FE follower/observer routing and multi-FE failover** (PLAN §9.1 "FE follower/observer routing and multi-host failover") was **not tested** — no multi-FE cluster was stood up. **Record as an explicit open P1+ obligation:** stand up a ≥3-FE cluster and prove failover before relying on it. |
| Produce concrete type & read-only capability ledger | **DONE** | **this document.** |

---

## G. Deltas the PLAN should absorb (for user review — PLAN not edited)

Places where live evidence contradicts or refines PLAN text. Listed for review; no
PLAN edits made.

1. **Metadata source (§4.4 → hard rule).** §4.4 says use `information_schema`
   "where Connector/J metadata is incomplete or misleading." Evidence: `getColumns`
   is lossy for **most** interesting types (LARGEINT→INT, IPv4/IPv6/VARIANT→OTHER,
   BITMAP→BIT, ARRAY→element-only, MAP/STRUCT→garbage INT). Promote to a **default**:
   drive column types from `information_schema.columns.COLUMN_TYPE`, not per-call
   discretion (PROBE §1, Impl #1).

2. **Decimal256 reason (§5).** §5 lists `DECIMAL > 38 → unsupported or VARCHAR`
   with implied wire/overflow risk ("No silent rounding/overflow"). Live: the
   **read path is wire-exact** for DECIMAL(76,10); the only limit is Trino's public
   DECIMAL ceiling of 38. The mapping outcome (VARCHAR/fail-loud) is right, but the
   *reason* is a Trino-type ceiling, not a wire limitation (PROBE Impl #3).

3. **LARGEINT is text, not numeric (§5/§11).** §5 implies a numeric conversion.
   Live: LARGEINT arrives as `String` (RSMD `CHAR`) and **`getBigDecimal` throws on
   the negative extreme**. The mapping must parse `getString`, not use
   `getBigDecimal` (PROBE §2/§3/Impl #2). Same for LARGEINT **array** elements
   (ARRAY §7.4).

4. **DATETIME/DATE getter hazards (§5).** §5 says "Direct after live boundary
   tests" (DATE) and "TIMESTAMP(p)" (DATETIME) without naming the getter. Live:
   `getDate`/`getTimestamp` **throw `YEAR` on `0000-*`** and `getTimestamp`
   **session-zone-shifts** DATETIME. Mandate `getObject(LocalDate/LocalDateTime)` /
   `getString` and **ban `getTimestamp`** for DATETIME (PROBE §3/§6/Impl #5,#6).

5. **DOUBLE Infinity is a getter failure, not just a pushdown concern (§5/§6.2).**
   §5 disables exact predicate pushdown for FLOAT/DOUBLE but does not flag the
   **read** failure. Live: `getDouble`/`getObject` **throw** on `Infinity`/
   `-Infinity`; and DOUBLE-max renders lossily to `Infinity` in arrays. Read via
   `getString`/fail-loud; never a blanket query error on one poisoned row (PROBE
   §3/Impl #6; STOCK; ARRAY F3).

6. **String-array native decoding is impossible, not merely deferred (§5 ARRAY
   gate, §6.2).** The ARRAY gate anticipated "strings containing comma, quotes,
   brackets…" as a test case; live proof is stronger: Doris emits string array
   elements **with zero escaping**, making them **provably ambiguous** — so
   `ARRAY<VARCHAR/CHAR/STRING>` is a **hard NO-GO** for native decoding in v1, and
   `ARRAY<JSON>` is **not creatable** at all. String-array index acceleration needs
   a separate superset-pre-filter design (ARRAY F4/F5/§7.6).

7. **Per-scan `SELECT *` metadata probe should be dropped (§4.4/§4.5).** Stock
   issues a companion `SELECT *` (`ReturnRows=1`) before each scan to read RSMD.
   Since types come from `information_schema`, the custom connector should **not**
   issue that probe (STOCK "Extra remote round-trips").

8. **DATETIME max precision is 6 (§5 "max proven precision").** Confirmed
   concretely: `DATETIME(6)` microseconds preserved; nanosecond Trino timestamps
   must not map into µs Doris (PROBE §3; PLAN §6.2 deny list) — fill in the "max
   proven precision" blank as **6**.

9. **brikk `FunctionHazard` needs the target name; and no artifact exists (§6.3/
   G3/G12).** §6.3 wants each rule pinned to "target function name and wrapper
   shape." The released type **cannot** return the target name, and **no released
   artifact exists at all** (only a pre-hazard SNAPSHOT). The plan's "mapping API
   released and pinned" P0 exit criterion is therefore **unmeetable without a
   `brikk-house` change** (additive `sourceName`/`targetName` + a non-SNAPSHOT
   release) — parked for approval (BRIKK (c)/(d)/(e)).

10. **Multi-FE failover unproven (§9.1).** §9.1 lists "FE follower/observer routing
    and multi-host failover" as a P0 probe item; only single-FE URL **syntax** was
    proven. Record as an explicit open P1+ obligation rather than an assumed-DONE
    P0 item (PROBE §11).

11. **BOOLEAN vs TINYINT discrimination must be is.COLUMN_TYPE-driven (§5).** §5
    says "Normalize Doris/Connector-J TINYINT/BIT reporting explicitly" — live proof
    pins the mechanism: they are protocol-indistinguishable (both `tinyint(1)`), so
    discrimination **must** read `COLUMN_TYPE` (`boolean` vs `tinyint`); metadata
    alone cannot do it (PROBE §4/Impl #7; STOCK).

---

*End of ledger. Decision-grade for P1/P2 implementation. Not committed.*
