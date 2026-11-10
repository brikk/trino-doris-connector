# LEDGER — StarRocks `contrib/trino-connector` audit (P0 / gate G11)

**Purpose:** one-time prior-art audit for `jvm/trino-doris` (Trino 483, Kotlin, Base JDBC on the
SingleStore model). This is a keep/reject ledger of *design ideas*, not a code source. Per
`AGENTS.md` and PLAN G11: **port no code wholesale**; StarRocks is never a Doris semantic source.

## Provenance / pin

- **Upstream repo:** `https://github.com/StarRocks/starrocks`
- **Audited commit:** `9fa42200b6a8abe6b93b7cbf9f38fa75334e6c0b`
  (`2026-07-18`, tip of `main` at audit time)
- **Module path:** `contrib/trino-connector/`
- **Trino version the module targets:** **418** (`contrib/trino-connector/pom.xml:9`,
  `<version>418</version>`; all `io.trino:*` deps use `${project.version}`).
- **JDBC driver:** MariaDB Connector/J `3.3.2` (`pom.xml:116-119`), *not* MySQL Connector/J. Chosen
  explicitly "for mysql because of gpl protocol" — `StarRocksJdbcConfig.transConnectionUrl`
  rewrites any `mysql` substring in the URL to `mariadb`
  (`StarRocksJdbcConfig.java:127-133`).
- **Mirrored docs cross-check:** `https://ta.thinkingdata.cn/presto-docs/connector/starrocks.html`
  is written for Trino **435** and drifts from this 418-pinned code (documents features/config
  keys and a type table that do not all match the checked-in implementation). Treat docs as
  non-authoritative; every claim below is cited to source, not docs.

**Cross-engine caveat:** this is the **StarRocks** connector. Doris and StarRocks forked years ago;
function catalogs, type wire formats, `information_schema` contents, and statistics tables have
diverged. brikk's Doris evidence and live Doris 4.1.3 probes remain authoritative for `trino-doris`.

---

## KEEP — lessons worth porting as *design ideas*

| # | Lesson | Source | Why keep (for `trino-doris`) |
|---|--------|--------|------------------------------|
| K1 | **Plugin/module shape matches our G1 plan.** `StarRocksPlugin extends JdbcPlugin` with `super("starrocks", new StarRocksClientModule())`; module binds `JdbcClient @ForBaseJdbc -> StarRocksClient`, installs `DecimalModule`, binds `StarRocksJdbcConfig`/`StarRocksConfig`. | `StarRocksPlugin.java:19-25`; `StarRocksClientModule.java:56-69` | Confirms the exact Base-JDBC wiring we intend (`DorisPlugin`/`DorisClientModule`/`DorisClient`). Mirror the shape; drop the write bindings (see R1). |
| K2 | **Connection-property baseline for a MySQL-wire non-MySQL engine.** Sets `tinyInt1isBit=false`, `useUnicode=true`, `characterEncoding=utf8`, `useInformationSchema=<configurable, default true>`, `connectTimeout`, `autoReconnect`/`maxReconnects`. | `StarRocksClientModule.java:94-110`; `StarRocksJdbcConfig.java:42-95` | Directly supports PLAN §4.3. `tinyInt1isBit=false` and explicit UTF-8 are must-haves for Doris. NOTE: `rewriteBatchedStatements=true` (line 101) is a **write** optimization — drop it in read-only. |
| K3 | **URL-must-not-contain-database validation.** `@AssertTrue isUrlWithoutDatabase()` rejects a catalog/db path in the JDBC URL (catalog-as-schema requires connecting without a default DB). | `StarRocksJdbcConfig.java:104-113,37-41` | Doris database→Trino schema mapping (PLAN G9/§4.4) needs the same guard in `DorisJdbcConfig`. |
| K4 | **Catalog-as-schema metadata mapping.** `listSchemas` reads `getCatalogs()`/`TABLE_CAT`; `getTableSchemaName` returns `TABLE_CAT`; `filterSchema` hides internal schemas (adds `sys`). | `StarRocksClient.java:264-290,337-342` | Same MySQL-wire pattern SingleStore uses; Doris FE also exposes databases as JDBC catalogs. Re-derive Doris' own system-schema hide-list (`information_schema`, `__internal_schema`, etc.) from live probe — do **not** copy `sys`. |
| K5 | **LIMIT is guaranteed.** `limitFunction` renders `... LIMIT n`; `isLimitGuaranteed=true`. | `StarRocksClient.java:772-782` | Matches PLAN §6.5. For Doris, still re-verify with a direct probe before trusting `isLimitGuaranteed`. |
| K6 | **Explicit NULL-ordering emulation in TopN** (the single most reusable idea). MySQL-family SQL has no `NULLS FIRST/LAST`; they emit a leading `ISNULL(col) ASC/DESC` sort key for the `ASC_NULLS_LAST` / `DESC_NULLS_FIRST` cases and pass the other two through natively. | `StarRocksClient.java:797-825` | PLAN §6.5 requires explicit Doris NULL ordering. Doris supports `NULLS FIRST/LAST` natively in newer versions — **verify on 4.1.3** whether to emit native syntax or the `ISNULL()` trick; keep the *pattern*, re-decide the *rendering*. |
| K7 | **TopN/aggregate/join disabled for textual (collation-risk) types.** `supportsTopN` returns false if any sort key is `CharType`/`VarcharType`; `supportsAggregationPushdown` uses `preventTextualTypeAggregationPushdown(groupingSets)`; `isSupportedJoinCondition` rejects char/varchar and `IS_DISTINCT_FROM`. | `StarRocksClient.java:784-795,252-257,856-867` | This is exactly PLAN G5 (collation is unsafe to assume). Adopt the "no pushdown on textual keys until collation proven" posture verbatim as a *policy*. |
| K8 | **REAL pushdown disabled** via `DISABLE_PUSHDOWN` because floats are approximate. | `StarRocksClient.java:397-404` | Matches PLAN §5 (FLOAT/DOUBLE exact-predicate hazard). Keep. |
| K9 | **Aggregate rewrite rule set** using Base JDBC `Implement*` rules: `ImplementCountAll`, `ImplementCount`, `ImplementMinMax(false)`, `ImplementSum`, `ImplementAvgFloatingPoint`, `ImplementAvgDecimal`, `ImplementStddev*`, `ImplementVariance*`, wired through `AggregateFunctionRewriter` + `JdbcConnectorExpressionRewriterBuilder.newBuilder().addStandardRules(this::quoted)`. | `StarRocksClient.java:220-239,246-262` | Good inventory of which Base-JDBC aggregate rules *exist*. For `trino-doris` (PLAN P4) enable them **one family at a time with proof** — do not adopt the whole set at once, and re-verify `stddev/variance` semantics on Doris. `ImplementMinMax(false)` (false = no text min/max) is a good default. |
| K10 | **Cost-aware join pushdown hook + FULL OUTER exclusion.** `implementJoin` returns empty for `JoinType.FULL_OUTER`, else delegates to `implementJoinCostAware(...)`; module installs `JdbcJoinPushdownSupportModule`. | `StarRocksClient.java:833-854`; `StarRocksClientModule.java:66` | Matches PLAN §6.5/P5 (cost-aware, FULL OUTER + textual-condition gates, off by default). Keep the shape; keep join disabled by default for Doris. |
| K11 | **Cancellation/abort contract.** `abortReadConnection` calls `connection.abort(directExecutor())` when the result set is not fully drained; `execute(...)` sets `statement.setQueryTimeout(...)` per statement. | `StarRocksClient.java:292-299,1257-1266` | Matches PLAN §4.3 (`Statement.cancel()` + server timeout). Adopt the abort-on-not-drained pattern; for Doris also test that cancel removes the query from `SHOW PROCESSLIST` (PLAN §4.3). NOTE their query-timeout is sourced from a **write** session property (see caveat under R1). |
| K12 | **Remote statistics via `information_schema` + histogram JSON — as a *pattern*, not a query.** `getTableStatistics` gated by `JdbcStatisticsConfig.isEnabled()`; `StatisticsDao` reads row count from `INFORMATION_SCHEMA.TABLES.TABLE_ROWS`, per-column cardinality/nullable from `INFORMATION_SCHEMA.STATISTICS` (first index column, no sub-part), and histograms from `INFORMATION_SCHEMA.COLUMN_STATISTICS` (parsed as JSON: `singleton`/`equi-height` buckets → NDV, null fraction). Guards a missing `COLUMN_STATISTICS` table by catching `ER_UNKNOWN_TABLE` (1109). | `StarRocksClient.java:869-945,1000-1074,1102-1155` | The *architecture* (statistics-enabled flag, `information_schema` row count, histogram→`ColumnStatistics`) transfers to PLAN P4. **Reject the specific SQL:** Doris' `information_schema` statistics surface differs; Doris exposes column stats via `SHOW COLUMN STATS`/`internal.__internal_schema` and its own histogram format. Re-derive every query against Doris 4.1.3. The `TABLE_ROWS`-is-inaccurate warning and "prefer index CARDINALITY over histogram NDV" heuristics are useful cautions. |
| K13 | **TIMESTAMP/TIME null-probing read functions.** Custom `LongReadFunction` overrides `isNull` to call `getObject(idx, LocalDateTime.class)` / `getObject(idx, String.class)` first, avoiding `java.sql.Timestamp`/`Time` conversion failures on out-of-range instants. | `StarRocksClient.java:485-527` | A real Connector/J correctness gotcha for DATETIME. Worth replicating in the Doris DATETIME read path (PLAN §5), then proving on live Doris DATETIME extremes. |
| K14 | **`json` mapped with `DISABLE_PUSHDOWN`** and parsed via `jsonParse(utf8Slice(getString(...)))`. | `StarRocksClient.java:378-379,976-983` | Matches PLAN §5 JSON policy (parse textual wire value; don't push JSON predicates blindly). Keep as default; `trino-doris` will add *specific proven* JSON predicate rules later (PLAN §6.2). |
| K15 | **DECIMAL overflow handling via `DecimalModule` / `ALLOW_OVERFLOW`** (rounding scale/mode from session; `precision > MAX_PRECISION` → unsupported). | `StarRocksClient.java:409-420`, `:65`(module) | Matches PLAN §5 (`DECIMAL precision > 38` → unsupported/no silent rounding in default mode). Keep the "break to unsupported when > MAX_PRECISION" branch. |

## REJECT — do not port

| # | Item | Source | Reason |
|---|------|--------|--------|
| R1 | **Entire write / Stream Load path.** `StreamLoadProperties`/`StreamLoadTableProperties` built in module; `StarRocksPageSinkProvider`/`StarRocksPageSink`/`StarRocksOperationApplier` push rows to BE via HTTP PUT `stream_load` (Apache HttpClient, JSON chunks, `strict_mode`, `strip_outer_array`); `beginInsertTable`/`finishInsertTable`, temp-table copy, `CREATE TABLE ... DISTRIBUTED BY HASH`, `ALTER TABLE`, rename/add-column, `setTableComment`. | `StarRocksClientModule.java:112-137,68`; `StarRocksPageSink.java`; `StarRocksOperationApplier.java`; `StarRocksClient.java:328-350,613-770,1157-1169` | `trino-doris` v1 is **read-only** (PLAN §2 non-goals, §7). Out of scope. Also note: the whole `com.starrocks.data.load.stream.*` package and `httpclient` dependency exist only for writes — none of it is needed. This is StarRocks-specific FE-plan/BE-pull-in-reverse (BE-push) design. |
| R2 | **`query_timeout` shipped as a *write* session property.** `getQueryTimeout` used by the read/DDL `execute()` path lives in `StarRocksWriteSessionProperties`. | `StarRocksWriteSessionProperties.java:32-71`; `StarRocksClient.java:165,1262` | The read-path timeout should not depend on a write-only session-properties class. `trino-doris` re-homes this as a first-class `doris.query-timeout` (PLAN §4.3) in `DorisSessionProperties`. |
| R3 | **Type policy — LARGEINT and complex types.** No dedicated `LARGEINT` branch at all: `bigint unsigned` → `DECIMAL(20,0)` (`createDecimalType(20)`), and any StarRocks `LARGEINT` (128-bit) falls through to `CONVERT_TO_VARCHAR`/unsupported. **No native ARRAY / MAP / STRUCT reader anywhere** — `toColumnMapping` has no `ARRAY`/`Types.ARRAY` case; array columns degrade to VARCHAR or are dropped. | `StarRocksClient.java:376-377,358-465` (whole `toColumnMapping`) | Explicitly rejected by PLAN G4/§5. `DECIMAL(20,0)` is far too narrow for a signed 128-bit type; `trino-doris` maps LARGEINT → `DECIMAL(38,0)` and **fails loud** on the unrepresentable extreme (PLAN §5, §11). Native ARRAY is a P0/P2 **requirement** for `array_contains`/`arrays_overlap` index acceleration — the connector's whole reason to exist — so the absence here is a hard reject. |
| R4 | **No expression/function pushdown beyond Base-JDBC standard rules.** Only `addStandardRules(this::quoted)` — no typed Doris/StarRocks-specific `ConnectorExpression` predicate rules (no `contains`→`array_contains`, no `arrays_overlap`, no JSON/regex mappings). | `StarRocksClient.java:220-222` | Not wrong, just absent. `trino-doris`'s value (PLAN §6.2) is precisely the typed, evidence-backed rules StarRocks never wrote. Nothing to port; noted so we don't assume prior art exists. |
| R5 | **MariaDB driver + `mysql`→`mariadb` URL string rewrite.** `transConnectionUrl` blindly `replace("mysql","mariadb")`. | `StarRocksJdbcConfig.java:127-133`; `pom.xml:116-119` | PLAN §2/§4.1 pins **MySQL Connector/J**, and SingleStore (our model) ships its own driver. The naive substring `replace` is also unsafe (mangles any URL/host/param containing `mysql`). Reject the driver choice and the rewrite. |
| R6 | **`useInformationSchema=true` as an unconditional default + `getCatalogs()` metadata reliance.** | `StarRocksClientModule.java:97`; `StarRocksClient.java:264-318` | Keep the *option* but re-probe: PLAN §4.4 says use `information_schema` queries *where Connector/J metadata is incomplete or misleading* — decide per-call from live Doris evidence, not a blanket driver flag. StarRocks' `information_schema` layout ≠ Doris'. |
| R7 | **`supportsRetries() = false` tied to non-transactional insert design.** | `StarRocksClient.java:241-244` | Retry posture here is entangled with the write path; re-decide independently for a read-only connector. |
| R8 | **PTF `query()` table function bound by default** (`newSetBinder(... ConnectorTableFunction).toProvider(Query.class)`). | `StarRocksClientModule.java:67` | PLAN G7.4 says **do not bind** the JDBC `query()` table function in v1 (read-only escape-hatch risk). Explicitly must-not-port. |

---

## Trino 418 → 483 API drift watchlist

The StarRocks module is pinned to Trino **418**. When translating any pattern above into Kotlin
against Trino **483**, the following class/method/package shapes have moved or changed. Verified
against `trino-singlestore` @ tag `483` (`SingleStoreClientModule.java`) and the 418 source cited.

| Area | Trino 418 shape (in StarRocks code) | Trino 483 shape (verify in 483 source) | Cite |
|------|-------------------------------------|----------------------------------------|------|
| **Table-function SPI package** | `import io.trino.spi.ptf.ConnectorTableFunction` | `io.trino.spi.function.table.ConnectorTableFunction` (the whole `io.trino.spi.ptf` package was renamed to `io.trino.spi.function.table`) | `StarRocksClientModule.java:39` vs 483 `SingleStoreClientModule` import |
| **JDBC PTF provider** | `io.trino.plugin.jdbc.ptf.Query` | still `io.trino.plugin.jdbc.ptf.Query` in 483 — but only relevant if you bind PTF; PLAN G7 says **don't** in v1 | `StarRocksClientModule.java:37,67` |
| **`DriverConnectionFactory` construction** | `new DriverConnectionFactory(new Driver(), url, props, credentialProvider)` (4-arg ctor) | **Builder only:** `DriverConnectionFactory.builder(driver, url, credentialProvider).setConnectionProperties(props).setOpenTelemetry(openTelemetry).build()`. `OpenTelemetry` is now an injected required arg. | `StarRocksClientModule.java:87-92` vs 483 `SingleStoreClientModule.createConnectionFactory` |
| **DI annotations** | `javax.inject.Inject` | `com.google.inject.Inject` (Guice) / `jakarta.inject` — `javax.inject` is gone from the Trino stack | `StarRocksClient.java:90`, `StarRocksWriteSessionProperties.java:23`, `StarRocksPageSinkProvider.java:29` |
| **Bean validation** | `javax.validation.constraints.*` (`@AssertTrue`, `@Min`, `@NotNull`, `@Size`) | `jakarta.validation.constraints.*` | `StarRocksJdbcConfig.java:27-28`, `StarRocksConfig.java:21-22`, `StarRocksWriteConfig.java:20-21` |
| **Decimal module install** | `install(new DecimalModule())` | `DecimalModule.withNumberMapping(ON_BY_DEFAULT)` / `binder.install(DecimalModule.withNumberMapping(...))` — the module gained an explicit number-mapping mode | `StarRocksClientModule.java:65` vs 483 `SingleStoreClientModule.configure` |
| **Metadata config for bulk columns** | (not set) | 483 SingleStore sets `configBinder.bindConfigDefaults(JdbcMetadataConfig.class, c -> c.setBulkListColumns(true))` — evaluate for Doris `getColumns` perf | 483 `SingleStoreClientModule.configure` |
| **Aggregate `Implement*` locations** | `io.trino.plugin.jdbc.aggregation.*` and `io.trino.plugin.base.aggregation.*` | Package layout largely stable through 483, but confirm each rule still exists (some `Implement*` signatures changed over releases). Re-import against 483, don't trust the 418 import list. | `StarRocksClient.java:47-57` |
| **`preventTextualTypeAggregationPushdown` / `implementJoinCostAware`** | static helpers on `BaseJdbcClient` / `JdbcJoinPushdownUtil` | Present in 483 but signatures evolved (e.g. join-pushdown util gained params across versions). Verify arity against 483 `JdbcJoinPushdownUtil`. | `StarRocksClient.java:132,256,847` |
| **`ConnectorPageSinkProvider` optional binder** | write-only; bound via `newOptionalBinder` | N/A for read-only `trino-doris` — do not bind (see R1) | `StarRocksClientModule.java:68` |
| **Guava/Guice/Airlift versions** | Guava 32, Guice 6, Airlift 230 (`pom.xml:15-19`) | Use the project Trino 483 BOM; do not pin these transitively (PLAN §4.1). | `pom.xml:13-23` |

**General rule:** treat every `import io.trino.*` and every `javax.*` in the StarRocks module as a
483 verification point. The safest reference for 483 shapes is the in-tree `trino-singlestore`,
`trino-mysql`, and `trino-base-jdbc` at tag `483`, per PLAN §4.2 — not this 418 module.
