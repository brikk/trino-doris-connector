# REPORT — `trino-doris` plugin assembly proof (Gate G12)

**Status: PROVEN CLEAN.** The future `jvm/trino-doris` plugin can be assembled with the
repo's existing convention plugins plus one small runtime-exclusion block, producing a
483-aligned plugin directory that contains `trino-base-jdbc`, MySQL Connector/J, and the
required airlift bundle, and **does not** contain `trino-spi` or any of the Trino
parent-first SPI jars (`io.airlift:slice`, `jackson-annotations`, `opentelemetry-api/context`).

This report is the only repo artifact. The spike used to prove it lives under
`/tmp/opencode/plugin-spike` and is intentionally not committed.

---

## 1. Repo build conventions (verified)

| Fact | Value | Source |
|---|---|---|
| Trino version pin | **483** | `jvm/gradle/libs.versions.toml:12` (`trino = "483"`) |
| Trino BOM artifact | `io.trino:trino-root:483` | `libs.versions.toml:52` (`trino-bom`) |
| MySQL Connector/J pin | **9.7.0** | `libs.versions.toml:14` (`mysql = "9.7.0"`) |
| Connector/J that Trino 483 BOM manages | **9.7.0** | `trino-root-483.pom` dependencyManagement — matches repo pin exactly |
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin toolchain | JDK 25 | `build-logic/.../buildlogic.kotlin.common.gradle.kts:8` |

### How `trino-ducklake` is assembled/packaged

- **NOT a shadow/uber jar.** The plugin is a **directory of jars**. `trino-ducklake`
  registers `pluginAssemble` (`trino-ducklake/build.gradle.kts:301-306`):

  ```kotlin
  val pluginAssemble by tasks.registering(Copy::class) {
      dependsOn(tasks.jar)
      into(layout.buildDirectory.dir("trino-plugin/trino-ducklake-$version"))
      from(tasks.jar)
      from(configurations.runtimeClasspath)   // <-- plugin dir contents = jar + runtimeClasspath
  }
  ```

  So **whatever is on `runtimeClasspath` is exactly what lands next to the connector jar**
  in the Trino plugin directory. Nothing prunes it further. This is the crux of G12: the
  provided/SPI set must be kept OFF `runtimeClasspath`, not merely off `compileClasspath`.

- **Provided-scope handling of `trino-spi`.** `trino-ducklake` declares the SPI/provided set
  as `compileOnly` (`build.gradle.kts:71-77`): `trino-spi`, `io.airlift:slice`,
  `opentelemetry-api`/`-api-incubator`/`-context`, `jackson-annotations`. In Gradle,
  `compileOnly` keeps a dep off `runtimeClasspath` **only if nothing else pulls it back in
  transitively**. For `trino-ducklake`'s current dep set that happens to be true for
  `trino-spi` (it is `provided` in every upstream Trino pom) but is a latent trap for
  `slice`/`jackson-annotations` once `trino-base-jdbc` is added (see §3).

- **Tests launch Trino in-process** via `io.trino:trino-testing`
  `DistributedQueryRunner`/`QueryRunner` (e.g. `DucklakeQueryRunner.kt`, and every
  `TestDucklake*` `createQueryRunner()`), with the plugin installed through the SPI on the
  test classpath. Test scope re-adds `trino-spi`/`slice`/`trino-main`/`trino-testing`
  (`build.gradle.kts:87-125`) so the in-process engine has the real SPI — that does not
  affect the shipped plugin dir.

- **Convention plugins** (`build-logic/src/main/kotlin/`):
  - `buildlogic.kotlin.common` — Kotlin JVM, JDK 25 toolchain, custom `src` / `test/src`
    layout, JUnit 5.
  - `buildlogic.kotlin.library` — thin alias of `common`.
  - `buildlogic.kotlin.brikk` — wires the Central Portal snapshots repo for the
    **TEST-ONLY** `dev.brikk.house` artifacts. Not needed for plugin assembly.

  None of these do plugin assembly; that logic is per-module in `build.gradle.kts`.

---

## 2. What `trino-base-jdbc:483` pulls in, and the classloader contract

### Upstream reference — `plugin/trino-mysql/pom.xml` @ tag 483

Packaging is `trino-plugin`. Dependencies split into:

- **Bundled (compile scope):** `guava`, `guice:classes`, **`mysql-connector-j`**,
  `io.airlift:configuration/json/log/units`, `trino-base-jdbc`, `trino-plugin-toolkit`,
  `jakarta.validation-api`, `jdbi3-core`.
- **`provided` (Trino supplies at runtime, NOT bundled):** `jackson-annotations`,
  `io.airlift:slice`, `opentelemetry-api`, `opentelemetry-api-incubator`,
  `opentelemetry-context`, **`trino-spi`**.

The `trino-plugin` Maven packaging strips `provided` deps (and their provided transitives)
from the plugin dir. The Gradle build here must reproduce that strip explicitly (§3).

### Why `provided` is safe — the classloader is parent-first for these packages

`io.trino.server.PluginManager` (core, tag 483) builds each `PluginClassLoader` with a
hard-coded parent-first `SPI_PACKAGES` list:

```
io.trino.spi.
com.fasterxml.jackson.annotation.
io.airlift.slice.
io.opentelemetry.api.
io.opentelemetry.context.
org.locationtech.jts.
+ a few Jackson-Blackbird leaf functional interfaces
```

Any class in those packages is **always loaded from the engine**, never from the plugin
dir. Consequences:

1. `trino-spi`, `slice`, `jackson-annotations`, `opentelemetry-api/context` MUST come from
   the engine (shared class identity — `Type`, `Block`, `Slice`, connector-bean Jackson
   annotations, OTel `Context` all cross the plugin/engine boundary).
2. A duplicated copy of those jars in the plugin dir is dead weight and a version-drift
   footgun; keep them out.
3. Everything else — guava, guice, jackson-databind/core, airlift bootstrap/json/log,
   jdbi, the JDBC driver, `opentelemetry-jdbc`/`-instrumentation`/`-semconv` (which are
   **not** in the parent-first list) — is plugin-local and MUST be bundled. This is why
   each JDBC plugin ships its own guice/jackson-databind and its own driver.

---

## 3. The proof (spike at `/tmp/opencode/plugin-spike`)

A throwaway Gradle project resolved `io.trino:trino-base-jdbc` (managed by the
`io.trino:trino-root:483` BOM) + `com.mysql:mysql-connector-j:9.7.0`, mirroring
`trino-ducklake`'s conventions (BOM platform; SPI set `compileOnly`; driver `runtimeOnly`;
plugin dir = `jar + runtimeClasspath`).

### 3a. Hazard reproduced — `compileOnly` is NOT enough

`trino-base-jdbc`'s own pom (`~/.gradle/caches/.../trino-base-jdbc-483.pom`) declares:

- `trino-spi` → `provided`  → absent from `runtimeClasspath` ✅ (no action needed)
- `opentelemetry-api` → `provided` → absent from `runtimeClasspath` ✅
- `jackson-annotations` → **compile** → leaks onto `runtimeClasspath` ❌
- `io.airlift:slice` → **compile** → leaks onto `runtimeClasspath` ❌
- `opentelemetry-context` leaks via `io.airlift:json` → ❌

Resolving with only the `compileOnly` block (identical to `trino-ducklake`) still left the
following on `runtimeClasspath` — i.e. they would be **wrongly bundled** into the plugin dir:

```
com.fasterxml.jackson.core:jackson-annotations:2.22
io.airlift:slice:2.8
io.opentelemetry:opentelemetry-api:1.64.0
io.opentelemetry:opentelemetry-api-incubator:1.64.0-alpha
io.opentelemetry:opentelemetry-context:1.64.0
```

`compileClasspath − runtimeClasspath` = `{ trino-spi }` only. That confirms `compileOnly`
alone excludes just `trino-spi`; the other four parent-first jars survive as
`implementation` transitives of base-jdbc / plugin-toolkit / airlift-json.

### 3b. Fix proven — explicit `runtimeClasspath` exclusions

Adding a `configurations.named("runtimeClasspath") { exclude(...) }` block for
`slice`, `jackson-annotations`, `opentelemetry-api`, `opentelemetry-api-incubator`,
`opentelemetry-context` produced:

```
PROOF OK: no provided/parent-first SPI jars in plugin dir
```

Post-exclusion assertions (all hold):

- **(a) `trino-spi` NOT in the list** — never was (base-jdbc marks it `provided`).
- **(b) No duplicate parent-first classes** — `slice`, `jackson-annotations`,
  `opentelemetry-api/-context` all removed; they load parent-first from the engine, exactly
  as `trino-ducklake` already treats them.
- **(c) No version duplications** — single `guava:33.6.0-jre`, single `guice:7.0.0`, single
  `jackson-*:2.22.1`, all `io.airlift:*` at `439` (units `1.13`). Connector/J `9.7.0`.

---

## 4. Resolved jar list for the future plugin (grouped)

After the recommended dependency + exclusion block, the plugin dir
(`trino-plugin/trino-doris-<version>/`) contains the connector jar plus:

### 4a. base-jdbc cluster (Trino)
```
io.trino:trino-base-jdbc:483
io.trino:trino-cache:483
io.trino:trino-matching:483
io.trino:trino-plugin-toolkit:483
```

### 4b. JDBC driver
```
com.mysql:mysql-connector-j:9.7.0      (Doris FE speaks MySQL wire on 9030)
```

### 4c. bundled airlift + DI + jackson-databind + misc (plugin-local, correct to bundle)
```
io.airlift:bootstrap:439  concurrent:439  configuration:439  http-client:439  json:439
io.airlift:log:439  log-manager:439  node:439  secrets-spi:439  security:439  stats:439
io.airlift:units:1.13
com.google.guava:guava:33.6.0-jre (+ failureaccess, listenablefuture, j2objc-annotations)
com.google.inject:guice:7.0.0  (bundle with classifier=classes — see §6)  aopalliance:1.0
com.fasterxml.jackson.core:jackson-core:2.22.1  jackson-databind:2.22.1
com.fasterxml.jackson.datatype:jackson-datatype-{guava,jdk8,joda,jsr310}:2.22.1
com.fasterxml.jackson.module:jackson-module-{blackbird,parameter-names}:2.22.1
jakarta.validation:jakarta.validation-api:3.1.1  jakarta.annotation/inject
org.hibernate.validator:hibernate-validator:9.1.2.Final (+ classmate, jboss-logging)
dev.failsafe:failsafe:3.3.2  org.weakref:jmxutils:1.29  joda-time:joda-time:2.14.2
info.debatty:java-string-similarity:2.0.0  org.jspecify:jspecify:1.0.0  org.ow2.asm:asm
org.antlr:antlr4-runtime:4.13.2  org.gaul:modernizer-maven-annotations  org.hdrhistogram:HdrHistogram
# opentelemetry instrumentation (NOT parent-first — correctly bundled, base-jdbc uses it):
io.opentelemetry:opentelemetry-jdbc / -instrumentation-api(/-incubator) / -semconv(-incubating)
# slf4j/log4j bridges + jetty http-client (airlift http-client transitives)
org.slf4j:*  org.apache.logging.log4j:*  org.eclipse.jetty*:12.1.11  ch.qos.logback:logback-core
io.github.wasabithumb:jtoml-all / recsup
```

### 4d. EXCLUDED — provided / parent-first (engine supplies; MUST NOT bundle)
```
io.trino:trino-spi                       (already provided by base-jdbc pom)
io.airlift:slice
com.fasterxml.jackson.core:jackson-annotations
io.opentelemetry:opentelemetry-api
io.opentelemetry:opentelemetry-api-incubator
io.opentelemetry:opentelemetry-context
```

---

## 5. Hazards found

1. **`compileOnly` ≠ excluded from the plugin dir (primary hazard).** `trino-ducklake`'s
   pattern of listing the SPI set as `compileOnly` is necessary but **insufficient** once
   `trino-base-jdbc` enters, because base-jdbc/plugin-toolkit/airlift-json pull `slice`,
   `jackson-annotations`, and `opentelemetry-context` back in at **compile scope**. Without
   the explicit `runtimeClasspath` exclusions in §6, the plugin dir would ship duplicate
   copies of parent-first SPI jars. (Not a class-identity crash — they load parent-first —
   but wrong, version-drift-prone, and divergent from upstream's `provided` contract.)

2. **`trino-spi` is safe by luck, not by the `compileOnly` line.** It is `provided` in every
   upstream pom, so it is already off `runtimeClasspath`. Keep the `compileOnly("io.trino:trino-spi")`
   anyway for compile-time SPI access and intent-documentation; do not rely on it for
   runtime pruning.

3. **guice classifier.** The resolved graph shows `guice:7.0.0` without the `classes`
   classifier. Upstream trino-mysql and this repo's `trino-ducklake` both request
   `guice` with `classifier=classes` (the variant that does not shade Guava). Carry that
   over to avoid a second Guava-shaded Guice on the plugin classpath.

4. **opentelemetry split is subtle.** Only `opentelemetry-api`, `-api-incubator`, `-context`
   are parent-first/provided. `opentelemetry-jdbc`, `-instrumentation-*`, `-semconv*` are
   plugin-local and MUST stay bundled (base-jdbc instruments JDBC via them). Do **not**
   blanket-exclude `io.opentelemetry` by group — exclude only the three api/context modules.

5. **No shadow jar means duplicate-class risk is per-jar, not per-class.** Since the plugin
   is a jar dir (not shaded), the only real duplication vector is bundling a jar whose
   package is parent-first. §6 covers exactly those.

6. **airlift version coupling.** All `io.airlift:*` resolve to `439` via the BOM except
   `units` (`1.13`) and `slice` (`2.8`, excluded). Do not pin airlift versions independently;
   let the Trino BOM drive them, matching `trino-ducklake`.

---

## 6. Recommended `build.gradle.kts` dependency + exclusion block for `jvm/trino-doris`

Reuses the repo conventions verbatim; the **only** new machinery vs `trino-ducklake` is the
`runtimeClasspath` exclusion block (item marked ★).

```kotlin
plugins {
    id("buildlogic.kotlin.library")
    // id("buildlogic.kotlin.brikk")   // add only when the TEST-ONLY brikk metadata dep lands
    java
    alias(libs.plugins.detekt)
}

version = "483-1-ALPHA"

repositories { mavenCentral() }

dependencies {
    // Trino 483 BOM drives io.trino / io.airlift / jackson / opentelemetry / mysql-connector-j.
    implementation(platform(libs.trino.bom))
    testImplementation(platform(libs.trino.bom))
    implementation(enforcedPlatform(libs.kotlin.bom)) {
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
    }

    // --- Bundled runtime (lands in the plugin dir) ---
    implementation("io.trino:trino-base-jdbc")
    implementation("io.trino:trino-plugin-toolkit")
    implementation("com.google.guava:guava")
    implementation("com.google.inject:guice") { artifact { classifier = "classes" } } // ← §5.3
    implementation("io.airlift:configuration")
    implementation("io.airlift:json")
    implementation("io.airlift:log")
    implementation("io.airlift:units")
    implementation("jakarta.validation:jakarta.validation-api")

    // Doris FE speaks the MySQL wire protocol; Connector/J 9.7.0 is BOM-aligned with Trino 483.
    runtimeOnly(libs.mysql.jdbc)            // com.mysql:mysql-connector-j:9.7.0
    runtimeOnly("io.airlift:log-manager")

    // --- Provided / parent-first SPI set: compile against, never bundle ---
    compileOnly("io.trino:trino-spi")
    compileOnly("io.airlift:slice")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.opentelemetry:opentelemetry-api")
    compileOnly("io.opentelemetry:opentelemetry-api-incubator")
    compileOnly("io.opentelemetry:opentelemetry-context")

    // ★ REQUIRED: keep the parent-first SPI jars OUT of the plugin dir.
    // trino-base-jdbc/plugin-toolkit/airlift-json pull slice + jackson-annotations +
    // opentelemetry-context back in at COMPILE scope, so compileOnly alone does not exclude
    // them from runtimeClasspath. trino-spi + opentelemetry-api are already provided-scope
    // upstream, but excluding them here is harmless and documents intent.
    // Proven at /tmp/opencode/plugin-spike -> "PROOF OK: no provided/parent-first SPI jars".
    configurations.named("runtimeClasspath") {
        exclude(group = "io.trino", module = "trino-spi")
        exclude(group = "io.airlift", module = "slice")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
        exclude(group = "io.opentelemetry", module = "opentelemetry-api")
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
        exclude(group = "io.opentelemetry", module = "opentelemetry-context")
        // NOTE: do NOT exclude io.opentelemetry by group — -jdbc/-instrumentation/-semconv
        // are plugin-local and required by base-jdbc.
    }

    // --- Test scope: real engine + SPI in-process (mirror trino-ducklake) ---
    testImplementation("io.trino:trino-spi")
    testImplementation("io.airlift:slice")
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("io.trino:trino-main")
    testImplementation("io.trino:trino-testing")
    testImplementation("io.airlift:testing")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.mysql)   // Doris live tests: see G12/§9 probe plan
}

// Same plugin-dir assembly as trino-ducklake: jar + runtimeClasspath (dir of jars, NOT shaded).
val pluginAssemble by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("trino-plugin/trino-doris-$version"))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}
tasks.build { dependsOn(pluginAssemble) }
```

Add `include(":trino-doris")` to `jvm/settings.gradle.kts` when scaffolding P1.

---

## 7. Answers to the G12 questions

- **Assembly proven clean?** Yes. With the §6 block the 483-aligned plugin dir bundles
  base-jdbc + plugin-toolkit + Connector/J + airlift/guice/jackson-databind and excludes
  every parent-first/provided SPI jar. Verified at `/tmp/opencode/plugin-spike`
  (`printPluginDirExcluded` → "PROOF OK").
- **Key exclusions needed:** `io.airlift:slice`, `com.fasterxml.jackson.core:jackson-annotations`,
  `io.opentelemetry:opentelemetry-api`, `-api-incubator`, `-context` on `runtimeClasspath`
  (`trino-spi` already provided). Exclude by module, never blanket `io.opentelemetry` by group.
- **Connector/J version + why:** **9.7.0** — it is what the Trino 483 BOM (`trino-root:483`)
  manages for `com.mysql:mysql-connector-j` and it is already the repo's `libs.versions.toml`
  pin (`mysql = "9.7.0"`, used today by `trino-ducklake`). Zero drift; use `libs.mysql.jdbc`.
- **Reuse of the existing convention plugin:** `buildlogic.kotlin.library` is reused as-is.
  The per-module `pluginAssemble` (jar + `runtimeClasspath`) is copied from `trino-ducklake`.
  The one addition base-jdbc forces is the `runtimeClasspath` exclusion block; `compileOnly`
  alone (trino-ducklake's approach) is insufficient once base-jdbc is present.

## 8. Open hazards / follow-ups (not blocking scaffolding)

- **guice `classes` classifier** must be carried over (§5.3); verify the assembled dir has
  no second Guava-shaded guice.
- **Connector/J behavior flags** (`tinyInt1isBit=false`, streaming, TZ) are a connection
  concern (plan §4.3), not an assembly concern — out of scope for G12 but pin them in P1.
- **doris-ducklake SNAPSHOT breaks offline root config.** The proof was run as a standalone
  spike, not as an included module, to avoid the `:doris-ducklake` 1.2-SNAPSHOT resolution
  in `jvm/settings.gradle.kts`. When `:trino-doris` is added to settings, ensure its
  configuration does not transitively force that SNAPSHOT.
- **Re-run the spike online at scaffold time** to confirm the BOM graph is unchanged if the
  Gradle/Maven caches are ever pruned (this proof used the already-cached 483 artifacts).
