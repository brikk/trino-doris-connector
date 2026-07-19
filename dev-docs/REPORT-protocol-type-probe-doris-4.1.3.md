# P0 protocol/type probe — Apache Doris 4.1.3 via MySQL Connector/J 9.7.0

Evidence for the planned read-only `trino-doris` connector (PLAN sections 3/G8,
4.3, 5, 9.1). Every claim below is backed by live output in
`evidence/probe-raw-output-4.1.3.txt` (the raw probe dump + FE-side appendices).
This is checked-in evidence, not ephemeral notes.

## Pinned versions

| Component | Exact value |
|---|---|
| Doris FE build | `doris-4.1.3-rc02-7126cf65d96` (SHOW FRONTENDS `Version`) |
| Doris BE build | `doris-4.1.3-rc02-7126cf65d96` (SHOW BACKENDS `Version`) |
| MySQL-protocol version string | `5.7.99` (`SELECT VERSION()` and `@@version`) |
| `version_comment` | `doris version doris-4.1.3-rc02-7126cf65d96` |
| JDBC driver | MySQL Connector/J `9.7.0` (rev `0aade1f13bcc98faf7dda5c02e782481eb291f62`) |
| Probe JVM | OpenJDK `21.0.2` (mise) |
| Cluster | isolated `trino-doris-dev` compose, stock `apache/doris:fe-4.1.3` + `be-4.1.3`, net `172.30.81.0/24`, host ports `9130->9030` (mysql) / `8130->8030` (http) |

Connection used by the probe:
`jdbc:mysql://127.0.0.1:9130/p0_probe?...&tinyInt1isBit=false&characterEncoding=UTF-8&sessionVariables=enable_decimal256=true,enable_agg_state=true`
(user `root`, no password).

### Type-creation feasibility (DDL) on stock 4.1.3

| Type | Creatable? | Requirement |
|---|---|---|
| BOOLEAN, TINYINT..BIGINT, FLOAT, DOUBLE, DECIMAL(9,2), DECIMAL(38,10) | yes | default |
| LARGEINT | yes | default |
| **DECIMAL(76,10) (Decimal256)** | yes | **only with `SET enable_decimal256=true`; otherwise `Column of type Decimal256 with precision 76 is not supported`** |
| DATE, DATETIME(0/3/6), CHAR, VARCHAR, STRING, JSON | yes | default |
| IPV4, IPV6, VARIANT | yes | default |
| ARRAY of all listed primitives, `ARRAY<ARRAY<INT>>`, `MAP<STRING,INT>`, `STRUCT<a:INT,b:STRING>` | yes | default (complex cols cannot be DUP/DIST keys) |
| BITMAP / HLL | yes | **AGGREGATE model only** (`BITMAP_UNION`/`HLL_UNION`); column name `hll`/`bitmap` is fine, the *type* keyword must be in an agg table |
| **AGG_STATE** | yes | **only with `SET enable_agg_state=true`**; needs `AGG_STATE<fn(args)> GENERIC` in an agg table |
| QUANTILE_STATE | not probed | (plan groups it with the opaque states) |

---

## Section 1 — `DatabaseMetaData.getColumns`

`getColumns` is **lossy** for Doris and must not be the sole source of truth.
Observed (`TYPE_NAME` / `DATA_TYPE` (java.sql.Types) / `COLUMN_SIZE` / `DECIMAL_DIGITS`; all columns `IS_NULLABLE=YES` except agg-model non-key columns which report `NO`):

| Doris column | getColumns TYPE_NAME | DATA_TYPE | COLUMN_SIZE | DECIMAL_DIGITS |
|---|---|---|---|---|
| BOOLEAN | `BOOLEAN` | 16 BOOLEAN | 3 | – |
| TINYINT | `TINYINT` | -6 TINYINT | 3 | – |
| SMALLINT | `SMALLINT` | 5 SMALLINT | 5 | – |
| INT | `INT` | 4 INTEGER | 10 | – |
| BIGINT | `BIGINT` | -5 BIGINT | 19 | – |
| **LARGEINT** | **`INT`** | **4 INTEGER** | **10** | – |
| FLOAT | `FLOAT` | 7 REAL | 12 | 0 |
| DOUBLE | `DOUBLE` | 8 DOUBLE | 22 | 0 |
| DECIMAL(9,2) | `DECIMAL` | 3 DECIMAL | 9 | 2 |
| DECIMAL(38,10) | `DECIMAL` | 3 DECIMAL | 38 | 10 |
| DECIMAL(76,10) | `DECIMAL` | 3 DECIMAL | 76 | 10 |
| DATE | `DATE` | 91 DATE | 10 | – |
| DATETIME(0/3/6) | `DATETIME` | 93 TIMESTAMP | 19 / 23 / 26 | – |
| CHAR(10) | `CHAR` | 1 CHAR | 10 | – |
| VARCHAR(100) | `VARCHAR` | 12 VARCHAR | 100 | – |
| STRING | `TEXT` | -1 LONGVARCHAR | 65535 | – |
| JSON | `JSON` | -1 LONGVARCHAR | 1073741824 | – |
| **IPV4 / IPV6 / VARIANT** | **`UNKNOWN`** | **1111 OTHER** | 65535 | – |
| ARRAY&lt;T&gt; (every element type) | reports the **element's** TYPE_NAME (e.g. `INT`, `BIGINT`, `DATETIME`) — the array-ness is invisible | element's code | element size | – |
| MAP / STRUCT | reports `INT` (garbage — the first child) | 4 INTEGER | 10 | – |
| BITMAP | `BIT` | -7 BIT | 1 | – |
| HLL / AGG_STATE | `UNKNOWN` | 1111 OTHER | 65535 | – |

**Implication:** the connector must resolve column types from
`information_schema.columns.COLUMN_TYPE` (Appendix C), **not** from
`getColumns`. That view reports the true Doris type strings:
`largeint`, `decimalv3(76, 10)`, `datetime(6)`, `ipv4`, `ipv6`, `variant`,
`array<...>`, `map<string,int(11)>`, `struct<int(11),string>`, `bitmap`, `hll`.
Note LARGEINT shows as `DATA_TYPE=bigint unsigned`, `COLUMN_TYPE=largeint` there.

---

## Section 2 — `ResultSetMetaData` (from a live `SELECT *`)

| Doris column | getColumnTypeName | getColumnType | getColumnClassName | precision | scale | isSigned |
|---|---|---|---|---|---|---|
| BOOLEAN (tinyInt1isBit=false) | `TINYINT` | -6 TINYINT | `java.lang.Integer` | 3 | 0 | true |
| TINYINT | `TINYINT` | -6 TINYINT | `java.lang.Integer` | 3 | 0 | true |
| SMALLINT | `SMALLINT` | 5 SMALLINT | `java.lang.Integer` | 5 | 0 | true |
| INT | `INT` | 4 INTEGER | `java.lang.Integer` | 10 | 0 | true |
| BIGINT | `BIGINT` | -5 BIGINT | `java.lang.Long` | 19 | 0 | true |
| **LARGEINT** | **`CHAR`** | **1 CHAR** | **`java.lang.String`** | 85 | 0 | false |
| FLOAT | `FLOAT` | 7 REAL | `java.lang.Float` | 12 | 0 | true |
| DOUBLE | `DOUBLE` | 8 DOUBLE | `java.lang.Double` | 22 | 0 | true |
| DECIMAL(9,2)/(38,10)/(76,10) | `DECIMAL` | 3 DECIMAL | `java.math.BigDecimal` | 9/38/76 | 2/10/10 | true |
| DATE | `DATE` | 91 DATE | `java.sql.Date` | 3 | 0 | false |
| DATETIME(0/3/6) | `DATETIME` | 93 TIMESTAMP | **`java.time.LocalDateTime`** | 6 | 0 | false |
| CHAR / VARCHAR | `CHAR` | 1 CHAR | `java.lang.String` | 85 | 0 | false |
| STRING | `TINYTEXT` | 12 VARCHAR | `java.lang.String` | 85 | 0 | false |
| JSON | `JSON` | -1 LONGVARCHAR | `java.lang.String` | 85 | 0 | false |
| IPV4 / IPV6 | `CHAR` | 1 CHAR | `java.lang.String` | 85 | 0 | false |
| VARIANT | `JSON` | -1 LONGVARCHAR | `java.lang.String` | 85 | 0 | false |
| **ARRAY / MAP / STRUCT (all)** | **`CHAR`** | **1 CHAR** | **`java.lang.String`** | 85 | 0 | false |

Notes:
- `precision=85` is a fixed placeholder Connector/J attaches to every text-shaped
  column; it is meaningless for type mapping.
- DATETIME's `getColumnClassName` is `java.time.LocalDateTime` (zoneless) —
  exactly what the plan wants for `TIMESTAMP(p)`.
- Complex types, LARGEINT, IPv4/IPv6 all arrive as **text over the wire**; the
  connector's decoders must parse `getString`, confirming PLAN §5's ARRAY gate.

---

## Section 3 — getter behavior (per edge row; verbatim in the raw dump)

### Scalars

- **BOOLEAN** `getObject`→`Integer` (0/1) under `tinyInt1isBit=false`;
  `getBoolean` works. `getString`→`"0"`/`"1"`.
- **TINYINT/SMALLINT/INT** → `Integer`; **BIGINT** → `Long`; min/max round-trip
  exactly (`-9223372036854775808`..`9223372036854775807`).
- **LARGEINT** → `getObject` returns a **`java.lang.String`** of the full 128-bit
  value (e.g. `170141183460469231731687303715884105727`). `getString` identical.
  `getBigDecimal(LARGEINT)` works for the max but **throws
  `SQLDataException: Invalid integer format` for the negative extreme**
  `-170141183460469231731687303715884105727` (row 2). See implications.
- **FLOAT** → `Float`; `-3.402823E38`/`3.402823E38` round-trip; **`NaN` reads
  fine** as `Float.NaN`.
- **DOUBLE** → `Double` for finite values; **`Infinity`/`-Infinity` make
  `getObject`/`getDouble` throw `SQLDataException: Value '∞' is outside of valid
  range for type java.lang.Double`**, while `getString` returns `"Infinity"` /
  `"-Infinity"`. `-0.0` reads as `-0.0`.
- **DECIMAL(9,2)/(38,10)/(76,10)** → `BigDecimal`, **full precision including
  Decimal256** (`getString`, `getBigDecimal`, `getObject` all exact for
  76-digit values). *Caveat: a bare huge numeric literal in `INSERT ... VALUES`
  is parsed as DOUBLE and loses precision on the way in — insert Decimal256
  literals via `CAST('...' AS DECIMALV3(p,s))`. This is an ingest artifact, not
  a read-path limit; the read path is faithful.*
- **DATE** → `java.sql.Date`; `9999-12-31` OK. **`0000-01-01`: `getDate`/`getObject`
  throw `SQLException: YEAR`**, `getString`→`"0000-01-01"`.
- **DATETIME(0/3/6)** → `LocalDateTime`, sub-second precision preserved to
  microseconds (`...789012`). `0000-01-01 00:00:00` reads via
  `getObject(LocalDateTime)` as `0000-01-01T00:00` but `getTimestamp` throws
  `YEAR`. Doris stored `DATETIME(0)` value `0000-01-01 00:00:00` (min is the
  zero date, not a Doris-specific epoch).
- **CHAR/VARCHAR/STRING** → `String`; empty string distinct from NULL.
- **JSON** → `String`, server-normalized (whitespace collapsed:
  `{"k": "v"}` stored, read back `{"k":"v",...}`).
- **IPV4/IPV6** → `String` in canonical text: `192.168.1.1`, `0.0.0.0`,
  `255.255.255.255`; `2001:db8::ff00:42:8329`, `::`, `fe80::1`,
  `ffff:ffff:...:ffff` (compressed IPv6 form).
- **VARIANT** → `String` (JSON text), e.g. `{"deep":{"a":[1,{"b":"中文"}]}}`.

### NULL handling
All-NULL row: every `getObject`/`getString` returns `<null>`; `wasNull` path intact.

---

## Section 4 — `tinyInt1isBit` false vs default(true)

| Setting | BOOLEAN column | TINYINT column |
|---|---|---|
| `tinyInt1isBit=false` | typeName `TINYINT`, type -6, class `Integer`, getObject `0/1`, getString `0/1` | typeName `TINYINT`, class `Integer` |
| `tinyInt1isBit=true` (driver default) | typeName **`BIT`**, type **-7**, class **`Boolean`**, getObject `true/false`, getString still `1/0` | typeName `TINYINT`, class `Integer` (unchanged) |

**Implication:** BOOLEAN and TINYINT are indistinguishable at the protocol level
(both are Doris `tinyint(1)`-shaped). With the default `tinyInt1isBit=true`,
*any* Doris TINYINT would be misreported as `BIT`/`Boolean`. The connector **must
set `tinyInt1isBit=false`** (matches PLAN §4.3) and drive BOOLEAN-vs-TINYINT
discrimination from `information_schema.columns.COLUMN_TYPE` (`boolean` vs
`tinyint`), never from the JDBC metadata alone.

---

## Section 5 — PreparedStatement parameters (client emulation vs server prep)

Both `useServerPrepStmts=false` (`ClientPreparedStatement`) and `=true`
(`ServerPreparedStatement`) executed and matched identically for `?`-bound
INT, BIGINT, DECIMAL(9,2), DATE, DATETIME(6), VARCHAR, and BOOLEAN predicates.
No type-fidelity difference observed (BOOLEAN `= true` matched ids `[1,3,4]`
under both — i.e. Doris treats the boolean literal as `1`). Server-side prepared
statements are functional on Doris 4.1.3 FE; the connector may use either mode,
though client emulation remains the safer default until a broader parameter-type
sweep is done.

---

## Section 6 — DATETIME under session time zones

Same `DATETIME(6)` value `2021-06-15 12:34:56.789012`, three connection zones
(`connectionTimeZone` + `forceConnectionTimeZoneToSession=true`; server
`@@system_time_zone=Etc/UTC`):

| connectionTimeZone | session `@@time_zone` | `getString(c_dt6)` | `getObject(LocalDateTime)` | `getTimestamp(c_dt6)` |
|---|---|---|---|---|
| UTC | `+00:00` | `2021-06-15 12:34:56.789012` | `2021-06-15T12:34:56.789012` | `2021-06-15 12:34:56.789012` |
| America/New_York | `America/New_York` | `2021-06-15 12:34:56.789012` | `2021-06-15T12:34:56.789012` | **`2021-06-15 16:34:56.789012`** |
| Asia/Shanghai | `Asia/Shanghai` | `2021-06-15 12:34:56.789012` | `2021-06-15T12:34:56.789012` | **`2021-06-15 04:34:56.789012`** |

**Implication:** Doris DATETIME is **zoneless**, exactly as PLAN §5 assumes. The
zoneless truth is preserved by `getString` and `getObject(LocalDateTime)` in all
zones. `getTimestamp` applies the client calendar and **shifts the instant** —
so the connector must read DATETIME via `getObject(LocalDateTime)` /
`getString`, **never `getTimestamp`**, to avoid a session-zone-dependent shift.

---

## Section 7 — streaming (1,001,000-row `nums` table, probe JVM `-Xmx256m`)

| Mode | rows | elapsed | rows/sec | peak heap during scan |
|---|---|---|---|---|
| default (no tuning) | 1,001,000 | 357 ms | ~2.8 M/s | 134 MB |
| `setFetchSize(Integer.MIN_VALUE)` (Connector/J row streaming) | 1,001,000 | 258 ms | ~3.9 M/s | **56 MB** |
| `useCursorFetch=true` + `setFetchSize(1000)` (server cursor) | 1,001,000 | 353 ms | ~2.8 M/s | 140 MB |

All three completed a full scan under `-Xmx256m` (no OOM). The
`Integer.MIN_VALUE` streaming mode has the clear memory advantage (~2.4× lower
peak) because it does not buffer the whole result. **Verdict: streaming works
against the Doris FE; use `setFetchSize(Integer.MIN_VALUE)` (matches
`MySqlClient`'s `enableStreamingResults()` pattern).** `useCursorFetch` also
works but offers no memory win here and adds a server round-trip per block.

---

## Section 8 — cancellation & query timeout

- **`Statement.cancel()`**: a `SELECT SLEEP(30)` running on one statement was
  cancelled from another thread. The runner returned in **~209 ms** after
  `cancel()` with `MySQLStatementCancelledException: Statement cancelled due to
  client request`. (A `COUNT(*)` self-join is *not* a valid cancellation target —
  Doris count-pushdown answers it in ~1 ms; `SLEEP` is the reliable probe.)
- **FE-side proof (Appendix B):** while `SELECT SLEEP(20)` runs it appears in
  `SHOW PROCESSLIST` (`Command: Query`, `Info: SELECT SLEEP(20)`). `KILL QUERY
  <id>` removes it from the processlist in **~18 ms**. `Statement.cancel()`
  issues exactly this KILL under the hood.
- **`SET query_timeout` (session):** with `query_timeout=3`, `SELECT SLEEP(30)`
  aborted at **3002 ms** with `SQLException: errCode = 2, detailMessage = query
  timeout`.

**Verdict:** the PLAN §4.3 cancellation contract (`Statement.cancel()` +
server `query_timeout`) is proven; both promptly release Doris work.

---

## Section 9 — session variables

| Variable | Default on 4.1.3 | SET at session scope works? |
|---|---|---|
| `query_timeout` | `900` (seconds) | yes → `123` |
| `exec_mem_limit` | `100147483648` (~100 GB) | yes → `2147483648` |
| `time_zone` | `Etc/UTC` | yes → `+08:00` |

All three are settable per-session via JDBC (`SET ...`) and via the JDBC URL's
`sessionVariables=`. This supports the PLAN §4.3 connector properties
(`doris.query-timeout`, `doris.exec-mem-limit`, `doris.time-zone`) applied at
session scope only.

---

## Section 10 — information_schema & metadata labeling

- `DatabaseMetaData.getTables(p0_probe)` labels base tables `TABLE` and the view
  `VIEW` correctly.
- `getTableTypes()` = `[LOCAL TEMPORARY, SYSTEM TABLE, SYSTEM VIEW, TABLE, VIEW]`.
- `information_schema.tables.TABLE_TYPE`: base tables = `BASE TABLE`
  (`ENGINE=Doris`); view = `VIEW` (`ENGINE=View`).
- `information_schema` exposes **55 tables/views** on 4.1.3 (full list in the raw
  dump), including `tables`, `columns`, `views`, `partitions`, `processlist`,
  `schemata`, `statistics`, `workload_groups`, etc.
- Catalogs visible via `getCatalogs()`: `__internal_schema`,
  `information_schema`, `mysql`, `p0_probe` (the internal Doris catalog's
  databases surface as JDBC "catalogs").
- **No materialized-view rows appeared in this probe** (none created). MV
  labeling in `getTables`/`information_schema.tables` still needs a dedicated
  probe before P1 exposes MVs (PLAN §4.4 open item).

---

## Section 11 — multi-host URL syntax

- Comma list form `jdbc:mysql://h1:p,h2:p/db` **connects** (proven against a
  single-host list pointed twice at the one FE); `SELECT 1` = 1.
- The `sequential:(host=..,port=..)` address form is **rejected** by Connector/J
  9.7.0 (`WrongArgumentException: Failed to parse the host:port pair`). Use the
  plain comma-list host syntax for FE failover, or a TCP load balancer
  (PLAN §4.3).

---

## Section — BITMAP / HLL / AGG_STATE selectability

- Selecting `bm_col` / `hll_col` / `aggst_col` **does not error**, but BITMAP and
  HLL come back as **`NULL` text** (`getString`→`<null>`; RSMD typeName `CHAR`).
  AGG_STATE returns **raw binary state bytes** as a string
  (e.g. `\x01\x01\x05\x00\x00\x00\x01\n\x00\x00\x00`) — not meaningful as a row
  value.
- The values are only usable through Doris functions: `bitmap_to_string(bm_col)`
  → `"100"`/`"200"`, `bitmap_count(bm_col)` → `Long 1`,
  `hll_cardinality(hll_col)` → `Long 1`.

**Implication:** BITMAP/HLL/QUANTILE_STATE/AGG_STATE must be **hidden/unsupported**
as row columns (PLAN §5), exactly as planned — they are opaque engine states, not
selectable values.

---

## Implications for `DorisClient` type mapping (surprises vs PLAN §5)

1. **`getColumns` is not authoritative — use `information_schema.columns`.**
   It narrows LARGEINT→`INT`, reports IPv4/IPv6/VARIANT/HLL/AGG_STATE as
   `OTHER`/`UNKNOWN`, BITMAP as `BIT`, and reports only the *element* type for
   ARRAY and garbage (`INT`) for MAP/STRUCT. `information_schema.columns.COLUMN_TYPE`
   gives the true strings (`largeint`, `decimalv3(76,10)`, `datetime(6)`, `ipv4`,
   `ipv6`, `variant`, `array<...>`, `map<...>`, `struct<...>`, `bitmap`, `hll`).
   **This is the single biggest surprise and should drive the metadata design.**

2. **LARGEINT arrives as text (`String`), not a numeric JDBC type.** RSMD reports
   it `CHAR`/`String`. Full 128-bit values read faithfully via `getString`.
   `getBigDecimal(LARGEINT)` **throws on the negative extreme**
   (`-2^127+1`). The connector's LARGEINT→DECIMAL(38,0) mapping must parse
   `getString` itself and fail loud on out-of-DECIMAL(38,0) range (PLAN §5/§11
   already prescribes this) — do **not** rely on `getBigDecimal`.

3. **DECIMAL > 38 (Decimal256) IS readable at full precision** via getString /
   getBigDecimal / getObject. Contrary to a pessimistic reading of PLAN §5
   ("unsupported or VARCHAR"), the *read path* is exact for DECIMAL(76,10). Two
   real constraints remain: (a) DDL requires `enable_decimal256=true`; (b) Trino's
   public DECIMAL contract stops at precision 38, so the connector still cannot
   expose a native Trino DECIMAL(76) — VARCHAR (or fail-loud) remains the mapping,
   but the choice is a Trino-type limitation, not a wire/driver one.

4. **IPv4/IPv6 read as canonical text** (`192.168.1.1`, compressed IPv6 like
   `2001:db8::ff00:42:8329`, `fe80::1`, `::`). This maps cleanly to Trino
   `IPADDRESS` if the parser accepts Doris's canonical forms; VARCHAR is the safe
   fallback (PLAN §5). No binary surprise.

5. **DATETIME precision is preserved to microseconds and is zoneless.** Read via
   `getObject(LocalDateTime)`/`getString`. **Never `getTimestamp`** — it shifts by
   the session zone (Section 6). `getTimestamp` also throws `YEAR` on `0000-*`
   dates; `getObject(LocalDateTime)` tolerates them.

6. **DATE `0000-01-01` and DOUBLE `Infinity`/`-Infinity` break the natural
   getters** (`SQLException: YEAR`, `NumberOutOfRange`). The connector's read
   functions must go through `getString` (or `getObject(LocalDateTime)` for
   datetime) and handle these edges, or fail loud — the default typed getters are
   not safe for the extremes.

7. **BOOLEAN vs TINYINT is protocol-ambiguous.** Must set `tinyInt1isBit=false`
   *and* discriminate from `information_schema` (Section 4). With the driver
   default, every Doris TINYINT would masquerade as `BIT`/`Boolean`.

8. **ARRAY/MAP/STRUCT/JSON/VARIANT are all text over the wire** (RSMD `CHAR`/
   `String`). Confirms PLAN §5's ARRAY gate: the connector owns the parser. See
   wire-format summaries below.

9. **BITMAP/HLL/AGG_STATE** are non-selectable as rows (NULL text or raw state
   bytes). Hide them (PLAN §5) — matches expectation.

### ARRAY / MAP / STRUCT / JSON / VARIANT wire-format (one-liners)

Exact bytes for every edge row are in the raw dump; the grammar is:

- **ARRAY**: `[elem, elem, ...]` — **space after comma**; NULL element = bare
  `null`; empty array = `[]`; whole-NULL array = SQL NULL (getString `<null>`).
  **Numbers/booleans are unquoted** (BOOLEAN renders as `1`/`0`, not `true`).
  **Strings, DATE, and DATETIME elements are double-quoted**
  (`["2021-01-01", "2021-12-31"]`, `["a", "b", "c"]`). Inside a quoted string
  element Doris escapes `"` as `\"` and `\` as `\\` **within the element text as
  returned by getString**, but commas/brackets/single-quotes/unicode are
  **literal** (e.g. `[null, "has,comma", "has'quote", "has\"dq", "has[br]",
  "has\\bs"]`; emoji/CJK/tab appear literally). Nested arrays nest directly:
  `[[1, null], null, []]`. **There is no header/type tag — element type must come
  from metadata, and the decoder must handle the quoting/escaping and the
  quoted-vs-bare distinction per element type.**
- **MAP**: `{"k":v, "k2":v2}` — keys always double-quoted, values follow the
  value-type rule (ints bare, space after comma inside/after entries). Empty map
  `{}`, NULL map = SQL NULL. Special/unicode keys literal: `{"k,1":1, "q'ok":2,
  "uni 中文":3}`.
- **STRUCT**: `{"a":10, "b":"hello"}` — rendered as a JSON-ish object keyed by
  **field name**, string fields double-quoted, NULL fields = `null`
  (`{"a":null, "b":null}`).
- **JSON**: server-normalized JSON text (whitespace collapsed), returned verbatim
  as the stored document (`{"k":"v","n":42,"arr":[1,2,3]}`).
- **VARIANT**: same normalized-JSON text shape as JSON
  (`{"deep":{"a":[1,{"b":"中文"}]}}`); RSMD even labels it `JSON`.

> Caution for the P2 ARRAY decoder: the array element separator is `, ` (comma
> **and space**), and string elements may themselves contain `, `, `[`, `]`, and
> escaped quotes/backslashes. A naive split on `,` will corrupt data — a real
> tokenizer that respects quoting and bracket depth is required (this is the PLAN
> §4/§12 core risk, now confirmed against live wire text).

---

## Verdicts (quick reference)

- **Streaming:** works; `setFetchSize(Integer.MIN_VALUE)` ~2.4× lower peak heap,
  full 1M scan under `-Xmx256m`.
- **Cancellation:** `Statement.cancel()` aborts in ~200 ms; `KILL QUERY` clears
  the FE processlist in ~18 ms; `query_timeout` aborts at the configured second.
- **Prepared statements:** client and server modes both fidelity-clean for the
  probed types.
- **Multi-host:** comma-list URL accepted; `sequential:(...)` form rejected.
- **Biggest surprise:** JDBC `getColumns` is lossy — the connector must read
  types from `information_schema.columns`. Decimal256 reads faithfully (only the
  Trino type ceiling, not the wire, limits it).

## Reproduce

```sh
cd jvm/trino-doris/compose && ./up.sh        # stock 4.1.3 on 127.0.0.1:9130
# throwaway probe harness (kept out of the repo) lives under /tmp/opencode/probe:
#   Probe.java (sections 1-11), setup.sql, data.sql, data2.sql, Dec256.java
# build: javac -cp mysql-connector-j-9.7.0.jar Probe.java
# run:   java -Xmx256m -cp .:mysql-connector-j-9.7.0.jar Probe
```

Cluster is left **running** for the follow-up ARRAY-decoder task.
