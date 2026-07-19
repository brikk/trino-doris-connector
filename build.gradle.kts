import java.util.zip.ZipFile

plugins {
    id("buildlogic.kotlin.library")
    java
    alias(libs.plugins.detekt)
}

// Default dev version. CI release flow (.github/workflows/release.yml) overrides this with
// -Pversion=<derived-from-branch> (e.g. release-483-2 -> 483-2). A plain Gradle script
// assignment would clobber the -P property (proven: it runs after Gradle applies project
// properties), so honor an explicit -Pversion when present and fall back to the dev default.
version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: "483-0.2.0-SNAPSHOT"

// Idiomatic-Kotlin quality gate, same setup as trino-ducklake.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src", "test")
}

repositories {
    mavenCentral()
}

// Dependency + exclusion block per dev-docs/REPORT-plugin-assembly-proof.md §6 (G12 "PROVEN CLEAN").
dependencies {
    // Trino 483 BOM drives io.trino / io.airlift / jackson / opentelemetry versions.
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
    implementation("com.google.inject:guice") {
        artifact {
            classifier = "classes" // assembly-proof §5.3: the non-Guava-shaded variant
        }
    }
    implementation("io.airlift:bootstrap")
    implementation("io.airlift:configuration")
    implementation("io.airlift:json")
    implementation("io.airlift:log")
    implementation("io.airlift:units")
    implementation("jakarta.validation:jakarta.validation-api")

    // Doris FE speaks the MySQL wire protocol; Connector/J 9.7.0 is BOM-aligned with Trino 483.
    // Deviation from assembly-proof §6 (runtimeOnly): DorisClientModule/DorisJdbcConfig reference
    // com.mysql.cj.jdbc.Driver + ConnectionUrlParser at compile time (same as upstream trino-mysql,
    // where the driver is compile scope), so this is `implementation`, not `runtimeOnly`.
    implementation(libs.mysql.jdbc)
    // brikk-sql-metadata: hazard-registry evidence for every pushdown rule (PLAN G3/§6.3) —
    // the featherweight embeddable module is the sanctioned PRODUCTION dependency. Bundles
    // kotlinx-serialization-core/json into the plugin dir (NOT in Trino's parent-first list,
    // so plugin-local is correct); kotlin-stdlib is already bundled (Kotlin connector).
    implementation(libs.brikk.sql.metadata)
    runtimeOnly("io.airlift:log-manager")

    // --- Provided / parent-first SPI set: compile against, never bundle ---
    compileOnly("io.trino:trino-spi")
    compileOnly("io.airlift:slice")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.opentelemetry:opentelemetry-api")
    compileOnly("io.opentelemetry:opentelemetry-api-incubator")
    compileOnly("io.opentelemetry:opentelemetry-context")

    // --- Test scope: real engine + SPI in-process (mirror trino-ducklake) ---
    testImplementation("io.trino:trino-spi")
    testImplementation("io.airlift:slice")
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("io.trino:trino-main")
    testImplementation("io.trino:trino-main") {
        artifact {
            classifier = "tests" // io.trino.sql.query.QueryAssertions (isFullyPushedDown plan assertions)
        }
    }
    testImplementation("io.trino:trino-testing")
    testImplementation("io.airlift:testing")
    // junit/assertj/kotlin-test come from the buildlogic.kotlin.common convention
}

// ★ REQUIRED (assembly-proof §3b/§6): keep the parent-first SPI jars OUT of the plugin dir.
// trino-base-jdbc/plugin-toolkit/airlift-json pull slice + jackson-annotations +
// opentelemetry-context back in at COMPILE scope, so compileOnly alone does not exclude
// them from runtimeClasspath. trino-spi + opentelemetry-api are already provided-scope
// upstream, but excluding them here is harmless and documents intent.
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

tasks.test {
    // The @Tag("cancellation") suite (TestDorisCancellation) is deliberately slow — it
    // submits a send-blocked cross-join and waits for Doris's server-side timeout sweep —
    // and flakes on shared/2-core CI runners. Cancellation is a works-or-obviously-broken
    // feature (its mechanism has the deterministic TestDorisClusterScopedCancel + overlay
    // cross-FE proofs), so it is EXCLUDED from the default build. Run it on demand with
    // `./gradlew test -PwithCancellation`.
    useJUnitPlatform {
        if (!project.hasProperty("withCancellation")) {
            excludeTags("cancellation")
        }
    }

    // Live P1a smoke tests target the already-running stock Doris 4.1.3 compose cluster
    // (./compose, mysql host port 9130). They fail loud if it is down.
    // 2g is ample for one in-process DistributedQueryRunner + the live suites (3g was
    // trino-ducklake heritage); a smaller test JVM also keeps host free memory above the
    // Doris BE's low-water mark (~1.3GB) when other builds run concurrently — below it the
    // BE fails ALL queries with MEM_ALLOC_FAILED regardless of its own usage.
    maxHeapSize = "1536m"

    // Same JVM flags the in-process Trino 483 engine needs in trino-ducklake's test task
    // (BlockEncodingSimdSupport requires jdk.incubator.vector, etc.).
    jvmArgs(
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:-OmitStackTraceInFastThrow",
        "--add-modules=jdk.incubator.vector",
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// Same plugin-dir assembly as trino-ducklake: jar + runtimeClasspath (dir of jars, NOT shaded).
val pluginAssemble by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("trino-plugin/trino-doris-$version"))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}

// G12 guard mirroring the assembly-proof check: the assembled plugin dir must not bundle any
// engine-provided (parent-first SPI) jar, must bundle base-jdbc + Connector/J, and must carry
// exactly one guice jar (the `classes` classifier).
val verifyPluginAssembly by tasks.registering {
    dependsOn(pluginAssemble)
    val pluginDir = layout.buildDirectory.dir("trino-plugin/trino-doris-$version")
    doLast {
        val jars = pluginDir.get().asFile.listFiles().orEmpty().map { it.name }.sorted()
        check(jars.isNotEmpty()) { "plugin dir is empty: ${pluginDir.get()}" }

        val forbiddenModules = listOf(
            "trino-spi",
            "slice",
            "jackson-annotations",
            "opentelemetry-api",
            "opentelemetry-api-incubator",
            "opentelemetry-context",
        )
        val offenders = jars.filter { jar ->
            forbiddenModules.any { module -> Regex("^${Regex.escape(module)}-\\d.*\\.jar$").matches(jar) }
        }
        check(offenders.isEmpty()) { "provided/parent-first SPI jars must not be bundled: $offenders" }

        val requiredPrefixes = listOf(
            "trino-base-jdbc-",
            "trino-plugin-toolkit-",
            "mysql-connector-j-",
            // the pushdown-evidence registry and its serialization runtime (plugin-local)
            "brikk-sql-metadata-jvm-",
            "kotlinx-serialization-core-jvm-",
            "kotlinx-serialization-json-jvm-",
        )
        val missing = requiredPrefixes.filter { prefix -> jars.none { it.startsWith(prefix) } }
        check(missing.isEmpty()) { "expected bundled jars missing (prefixes): $missing; got $jars" }

        val guiceJars = jars.filter { it.startsWith("guice-") }
        check(guiceJars.size == 1 && guiceJars.single().endsWith("-classes.jar")) {
            "expected exactly one guice jar with the 'classes' classifier, got: $guiceJars"
        }

        // A real Trino server discovers plugins from the plugin dir via ServiceLoader; the
        // programmatic installPlugin(...) used by tests bypasses it, so a missing registration
        // is invisible to every suite. Guard the jar entry + FQCN here (found missing 2026-07-19).
        val connectorJar = pluginDir.get().asFile.listFiles().orEmpty()
            .single { it.name.startsWith("trino-doris-connector-") }
        val serviceEntry = "META-INF/services/io.trino.spi.Plugin"
        val registered = ZipFile(connectorJar).use { zip ->
            zip.getEntry(serviceEntry)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText().trim()
            }
        }
        check(registered == "dev.brikk.trino.doris.DorisPlugin") {
            "connector jar must register DorisPlugin via $serviceEntry; found: $registered"
        }

        logger.lifecycle("verifyPluginAssembly OK: ${jars.size} jars, no provided/parent-first SPI jars, ServiceLoader registration present")
    }
}

tasks.build {
    dependsOn(pluginAssemble)
    dependsOn(verifyPluginAssembly)
}
