# Trino 484 upgrade qualification

Date: 2026-10-28. Branch/worktree: `trino-484`.

**Historical qualification.** The subsequent 2026-10-31
[golden-stack report](REPORT-trino-484-doris-4.1.5-lock.md) records the completed
4.1.5 / metadata 0.21.0 lock, full run, and R-04/R-05 rechecks. That newer run
remains release-blocked; the passing older baseline below must not replace it.

**Target change recorded on 2026-10-28:** Doris images were pinned to **4.1.5** in
both Compose topologies. The results below remain the **4.1.4.1 baseline**;
4.1.5 qualification had not yet run at this stage. Verified release indexes:
FE `sha256:bd0149d76fd02429ee4ab751f34c16948fbf761a886070489fdebf7b2069305c`,
BE `sha256:10d282e1bd685f8c0d71b3b42bbd0841bbb20909de2cd847923f00378b6b9a11`.
Both contain linux/amd64 and linux/arm64 images. Future brikk-sql evidence
refreshes should target **Trino 484 × Doris 4.1.5**.

**Outcome: the clean default build and real-server smoke pass.** The optional
multi-FE overlay and opt-in slow cancellation suite were not qualified here;
this is not a claim that every release-qualification scenario has passed.

## Scope

- Trino BOM, SPI, base-jdbc, toolkit, and test engine: **483 → 484**.
- MySQL Connector/J: **9.7.0 → 26.7.0**, matching the 484 BOM.
- Development connector version: `484-0.2.0-SNAPSHOT`.
- Manual server image: `trinodb/trino:484`, pulled as
  `sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f`.
- Doris stays at the digest-pinned **4.1.4.1** release. The existing single-FE
  volumes were retained (cluster ID `1945291672`); no data volumes were removed.
- JDK stays at 25; Kotlin and the `brikk-sql-metadata-jvm:0.7.0` evidence pin
  are unchanged to isolate the Trino/JDBC upgrade.
- No release branch was pushed and no connector release was published.

Upstream references:
[release notes](https://trino.io/docs/current/release/release-484.html#spi),
[484 BOM](https://repo.maven.apache.org/maven2/io/trino/trino-root/484/trino-root-484.pom),
[JSON mapping](https://github.com/trinodb/trino/blob/484/plugin/trino-base-jdbc/src/main/java/io/trino/plugin/jdbc/StandardColumnMappings.java),
[plugin classloader contract](https://github.com/trinodb/trino/blob/484/core/trino-main/src/main/java/io/trino/server/PluginManager.java).

## Required adaptations

The dependency-only upgrade compiles both production and test sources, with the
existing deprecated-API warnings. Compilation alone does not establish runtime
compatibility: the old JSON mapper still returns a Slice even though 484's JSON
type requires `io.trino.json.Json`.

1. Use upstream `StandardColumnMappings.jsonColumnMapping` for JSON and VARIANT.
   It parses remote JSON text into native Json objects, binds Json parameters as
   text, and keeps blanket JSON predicate pushdown disabled. The connector's
   separate guarded scalar-equality rewrite is not broadened.
2. Treat `trino-json` as engine-provided/parent-first, like `trino-spi`: compile
   and test against it, exclude it from the shipped runtime directory, and add
   it to the assembly's forbidden-JAR check.
3. Replace the legacy `com.google.inject:guice:7.0.0:classes` dependency with
   BOM-managed `io.airlift:guice:10`. The normal Airlift JAR is unshaded (459
   Guice classes and no shaded Guava classes); the assembly guard still requires
   exactly one Guice JAR.
4. Pin the new JSON mapping with cluster-free tests: native type and block
   round-trip, object order/duplicate keys/decimal precision, SQL NULL versus
   JSON null, malformed/overflowing/deep/invalid-Unicode rejection, and parameter
   binding. The live smoke expectation now preserves Doris wire object order
   rather than the old mapper's sorted keys.
5. Update the version-sensitive exponent-rendering pin: local extraction from
   native JSON now returns `1.0E30`, rather than 483's `1E+30`; Doris still
   returns `1e+30`. Numeric-looking equality literals remain excluded from
   pushdown. NUMBER values wider than DECIMAL(38) retain exact numeric value
   but normalize trailing zeroes; ordinary DECIMAL JSON values retain scale.

## Validation

Initial dependency-only compilation passed:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 \
  ./gradlew compileKotlin compileTestKotlin --no-daemon --console=plain
```

The dependency-only `./gradlew build --no-daemon --console=plain` run finished in
10m55s: **343 tests, 303 passed, 35 failed, 5 skipped, zero errors**. Every
failure contains the same native-JSON/Slice incompatibility. Metadata reads
of any table containing a JSON column also fail, even when the query selects
only non-JSON columns. Compilation and detekt passed; the build stopped at the
test gate before plugin assembly verification.

The five skips are the optional multi-FE overlay suite (overlay not started).
The slow `cancellation`-tagged suite is excluded by the normal build contract;
the remaining production cancellation suites do run.

Logs are under
`/tmp/opencode/trino-484-*.log`; the dependency-only XML is preserved under
`/tmp/opencode/trino-484-validation/initial/`. Completed runs are recorded
without reclassifying a failing full run as green based on an isolated retry.

First adapted-code focused run: **45 tests, 44 passed, one failed, zero skips**.
All existing smoke/type-mapping/read-only/evidence-drift tests passed, as did
detekt and plugin assembly verification (**79 JARs**). The new JSON regression
test incorrectly expected NUMBER to retain trailing zeroes beyond DECIMAL(38);
the test now explicitly pins value-preserving NUMBER normalization, while
still requiring DECIMAL scale, duplicate keys, and member order to survive.
Its original XML is under `trino-484-validation/focused/`.

Second focused JSON/P5 run: **20 tests, 19 passed, one failed, zero skips**.
All three new JSON mapping tests passed, including the corrected NUMBER pin.
The sole failure was the existing `1E+30` scalar-rendering expectation; both
the live connector and the independent 484 server returned `1.0E30`. The pin
and current-version hazard descriptions were updated, without changing the
registry's historical provenance or enabling any new pushdown. XML is under
`trino-484-validation/json-focused/`.

### Final clean default build

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 \
  ./gradlew clean build --no-daemon --console=plain
```

**BUILD SUCCESSFUL in 14m51s**: 45 suites, **346 tests, 341 passed, zero
failures/errors, 5 skipped**. Production/test compilation, detekt, the complete
default live-test suite, plugin assembly, and assembly verification all passed.
This includes the read-only JdbcClient reflection audit, production cancellation
suites, all 19 registry pins/artifact SHA checks, JSON differential/guard tests,
and the three new native JSON mapping tests. Deprecated-API warnings remain.

The five skips are solely the optional multi-FE overlay tests. The normal build
still excludes the slow cancellation-tagged suite; neither exclusion was added
or broadened by this change. Final XML is preserved under
`/tmp/opencode/trino-484-validation/adapted-full/`, separately from failed runs.
The log is `/tmp/opencode/trino-484-build-adapted.log`.

### Real-server smoke

Passed with the snapshotted 79-JAR plugin directory, loaded into
`trinodb/trino:484` via ServiceLoader, not the in-process test classpath:

- `SELECT version()` returned `484`.
- `CREATE CATALOG`, schema discovery, and `DROP CATALOG` succeeded.
- `count(*)` of `p0_probe.scalars_view` returned 8.
- JSON/VARIANT returned `{"k":"v","n":42,"arr":[1,2,3]}` and
  `{"vk":"vv","vn":7}` for scalar row 1.
- The selective ordered LIMIT query returned 1000996 and 1000997. EXPLAIN
  showed one TableScan with the `n` constraint, sort order, and limit=2;
  no local Filter/Sort/TopN remained.
- Independent engine JSON probes returned `1.0E30`, `0`, `1.00`, and
  `{"z":1,"a":2,"z":3}` for exponent, negative zero, DECIMAL scale,
  and duplicate-member/order cases respectively.

Repeated successfully against the **final clean-built** assembly with the same
catalog, JSON/VARIANT, scalar count, selective-result, and EXPLAIN checks. Final
logs are `/tmp/opencode/trino-484-server-final-{up,smoke}.log` and
`/tmp/opencode/trino-484-server-final.log`.

The smoke and single-FE validation containers were stopped afterward, restoring
their initial stopped state; no volumes were deleted. Initial smoke logs:
`/tmp/opencode/trino-484-server-{up,smoke}.log`,
`/tmp/opencode/trino-484-server.log`, and
`/tmp/opencode/trino-484-json-semantics.log`.

## brikk-sql coordination

No metadata API change is required by the native JSON mapping itself. Maven
Central initially listed `brikk-sql-metadata-jvm:0.20.1` on 2026-10-28; its published
`GeneratedTrinoDorisHazards.kt` is byte-identical to 0.7.0 (231,216 bytes,
SHA-256 `86dbe0116b041e465d4d46f2b8380841404c2fcc9504a2683f534fe8eb0e39fd`).
Both still cite the July 2026 differential probe, so upgrading that artifact
alone would not supply Trino 484 semantic evidence.

For a refreshed evidence release, requalify JSON scalar extraction/rendering and
path traversal against **Trino 484 × Doris 4.1.4.1**, retaining adversarial
numeric, NULL, object/array, and subscript cases. Existing connector guards
remain in force regardless of a broad registry verdict. A metadata upgrade
also needs a separate compiler/serialization compatibility check and deliberate
version/JAR-SHA pin updates; it is not silently included in this upgrade.

Confirmed 484 deltas worth including: exponent-form scalar rendering (`1.0E30`),
DECIMAL scale retention (`1.00`), NUMBER trailing-zero normalization, preserved
member order/duplicate keys, and newly rejected overflowing exponents or
unpaired Unicode surrogates. Keep the existing non-scalar, NULL, and subscript
hazards in the adversarial matrix rather than inferring safety from these
rendering improvements.

### Received 484 / 4.1.5 metadata handoff (release pending)

The user supplied the upstream report, raw capture, probe inputs, and reviewed
metadata source. The refreshed provenance is
`trino-484-doris-4.1.5-json.md#path-semantics` for extraction and
`trino-484-doris-4.1.5-json.md#numbers-and-serialization` for parse/format.
The upstream capture reports 47 paired cases (20 exact matches, 27 differences),
with explicit typed-JSON versus legacy-varchar inputs and SQL NULL versus JSON
null distinctions. These are supplied upstream observations, not a connector
4.1.5 replay. The reported amd64 manifests match the child manifests of our
multi-architecture FE/BE pins.

Of the five changed source entries, only `json_extract_scalar -> json_unquote`
is currently one of our 19 registered evidence pins. Its pending metadata changes
IDENTICAL to CONDITIONALLY_EQUIVALENT and replaces the historical provenance.
Our current artifact/version/SHA pins remain unchanged; changing them before
the consumer adaptation would intentionally fail construction/drift checks.
The connector does not use the brikk SQL generator, so its JSON_PARSE rendering
fix does not directly change connector SQL.

Before adopting a released artifact:

- Review the generated lookup result, not just raw pair JSON (the source also
  has a `json_extract_scalar -> get_json_string` alternative).
- Represent and verify the JSON rule's path/literal admission guards explicitly
  when adopting the conditional verdict. Merely changing the expected verdict,
  or naming the unquote wrapper as a sufficient guard, would not establish
  safety. The current treatment policy expects a rendered guard/wrapper;
  scope/admission guards require a deliberate, test-backed representation.
- Keep equality-only, top-level predicate scope, native JSON-only input, simple
  dotted paths, and numeric/container/NUL literal exclusions. The positive
  duplicate-key examples do not authorize new paths or functions.
- Re-probe stored JSON columns over Connector/J on 4.1.5: especially SQL NULL
  versus JSON null and exponent overflow/non-finite output. An SQL CAST-to-STRING
  result does not establish the native-column JDBC wire value. Unit tests pin
  fail-loud handling if `inf` reaches our JSON reader, but do not prove behavior
  of pushed versus local predicates over such stored values. The current
  numeric-looking literal regex does not classify the text `inf` as numeric.
- Re-run connector differentials and the evidence/version/JAR-SHA drift gate.
  Snapshots may be evaluated separately, but the release dependency must remain
  a reviewed non-SNAPSHOT artifact under the existing evidence contract.

Cluster-free regression cases now directly cover the supplied numeric/container
literal exclusions, negative/wildcard/quoted paths, ordinary text/boolean literal
admission in either equality orientation, and rejection of invalid non-finite
wire JSON. No production pushdown admission or dependency pin was changed.

Handoff-focused validation passed: **13 tests, zero failures/errors/skips**, plus
detekt, using the existing released metadata pin and no live cluster:

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew test \
  --tests '*TestDorisJsonExtractScalarEquality' \
  --tests '*TestDorisJsonMapping' \
  --tests '*TestDorisPushdownEvidenceDrift' detekt --no-daemon --console=plain
```

Log: `/tmp/opencode/trino-484-metadata-handoff-tests.log`; XML is preserved under
`/tmp/opencode/trino-484-validation/metadata-handoff/`. This does not exercise
the pending metadata artifact or substitute for a live 4.1.5 connector run.
