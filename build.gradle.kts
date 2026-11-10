import dev.detekt.gradle.extensions.FailOnSeverity
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    id("buildlogic.kotlin.library")
    java
    alias(libs.plugins.detekt)
}

// The release workflow passes -Pversion=<trino>-<semver>, derived from the release branch name.
// A plain assignment here would overwrite that property, so only fall back to the dev default.
version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: "484-0.6.0-SNAPSHOT"

// Version labels are also directory/archive names. Reject unsafe spellings before
// configuring any task output, not just in the GitHub publication step.
val numericVersion = "(?:0|[1-9][0-9]*)"
val prereleaseIdentifier = "(?:$numericVersion|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
val pluginVersionPattern = Regex("^([1-9][0-9]*)-$numericVersion\\.$numericVersion\\.$numericVersion(?:-$prereleaseIdentifier(?:\\.$prereleaseIdentifier)*)?$")
val versionMatch = requireNotNull(pluginVersionPattern.matchEntire(version.toString())) {
    "Plugin version must be <trino>-<major>.<minor>.<patch> with an optional SemVer prerelease (no paths or build metadata)"
}
val compiledTrinoVersion = libs.versions.trino.get()
require(versionMatch.groupValues[1] == compiledTrinoVersion) {
    "Plugin version $version claims Trino ${versionMatch.groupValues[1]}, but the build targets Trino $compiledTrinoVersion"
}

val verifyTrinoCompatibility by tasks.registering {
    doLast {
        // Check the resolved compile artifacts too: a catalog label alone is not
        // proof if a future dependency override changes the actual compiled ABI.
        val trinoArtifacts = configurations.compileClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "io.trino" }
        check(trinoArtifacts.any { it.name == "trino-spi" }) { "Compiled Trino SPI is missing" }
        check(trinoArtifacts.all { it.moduleVersion.id.version == compiledTrinoVersion }) {
            "Resolved Trino compile dependencies do not all match $compiledTrinoVersion: " +
                trinoArtifacts.map { it.moduleVersion.id }
        }
    }
}

tasks.register("validateReleaseVersion") {
    dependsOn(verifyTrinoCompatibility)
    doLast {
        check(!version.toString().contains("SNAPSHOT", ignoreCase = true)) { "Cannot publish a SNAPSHOT release" }
        logger.lifecycle("Release version $version matches compiled Trino $compiledTrinoVersion")
    }
}

// Idiomatic-Kotlin quality gate, same setup as trino-ducklake.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src", "test")
    // Warnings are advisory (config/detekt/detekt.yml); only errors fail the build.
    failOnSeverity = FailOnSeverity.Error
}

repositories {
    mavenCentral()
}

// Dependency + exclusion block per dev-docs/archive/REPORT-plugin-assembly-proof.md §6 (G12 "PROVEN CLEAN").
// Trino 484 JSON/Guice adaptations: dev-docs/archive/REPORT-trino-484-upgrade.md.
dependencies {
    // Trino 484 BOM drives io.trino / io.airlift / jackson / opentelemetry versions.
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
    // Trino 484/Airlift use the unshaded Airlift fork, not com.google.inject:guice.
    implementation("io.airlift:guice")
    implementation("io.airlift:bootstrap")
    implementation("io.airlift:configuration")
    implementation("io.airlift:json")
    implementation("io.airlift:log")
    implementation("io.airlift:units")
    implementation("jakarta.validation:jakarta.validation-api")

    // Doris FE speaks the MySQL wire protocol; Connector/J 26.7.0 is BOM-aligned with Trino 484.
    // Deviation from assembly-proof §6 (runtimeOnly): DorisClientModule/DorisJdbcConfig reference
    // com.mysql.cj.jdbc.Driver + ConnectionUrlParser at compile time (same as upstream trino-mysql,
    // where the driver is compile scope), so this is `implementation`, not `runtimeOnly`.
    implementation(libs.mysql.jdbc)
    runtimeOnly("io.airlift:log-manager")

    // --- Provided / parent-first SPI set: compile against, never bundle ---
    compileOnly("io.trino:trino-spi")
    compileOnly("io.trino:trino-json")
    compileOnly("io.airlift:slice")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.opentelemetry:opentelemetry-api")
    compileOnly("io.opentelemetry:opentelemetry-api-incubator")
    compileOnly("io.opentelemetry:opentelemetry-context")

    // --- Test scope: real engine + SPI in-process (mirror trino-ducklake) ---
    testImplementation("io.trino:trino-spi")
    testImplementation("io.trino:trino-json")
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
    // Hazard registry the pushdown rules were proven against; checked by TestDorisFunctionRegistryVerdicts.
    testImplementation(libs.brikk.sql.metadata)
    // junit/assertj/kotlin-test come from the buildlogic.kotlin.common convention
}

// ★ REQUIRED (assembly-proof §3b/§6): keep the parent-first SPI jars OUT of the plugin dir.
// trino-base-jdbc/plugin-toolkit/airlift-json pull slice + jackson-annotations +
// opentelemetry-context back in at COMPILE scope, so compileOnly alone does not exclude
// them from runtimeClasspath. trino-spi + opentelemetry-api are already provided-scope
// upstream, but excluding them here is harmless and documents intent.
configurations.named("runtimeClasspath") {
    exclude(group = "io.trino", module = "trino-spi")
    exclude(group = "io.trino", module = "trino-json")
    exclude(group = "io.airlift", module = "slice")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
    exclude(group = "io.opentelemetry", module = "opentelemetry-api")
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    exclude(group = "io.opentelemetry", module = "opentelemetry-context")
    // NOTE: do NOT exclude io.opentelemetry by group — -jdbc/-instrumentation/-semconv
    // are plugin-local and required by base-jdbc.
}

tasks.test {
    // Explicit harness permissions are inputs, not defaults silently enabling writes.
    inputs.property("dorisTestMutationOptIn", providers.environmentVariable("DORIS_TEST_ALLOW_MUTATION").orElse("unset"))
    inputs.property("dorisTestFailoverOptIn", providers.environmentVariable("DORIS_TEST_ALLOW_FAILOVER").orElse("unset"))
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

    // Live TestDorisConnectorSmoke tests target the already-running Doris compose cluster
    // (./compose, mysql host port 9130). They fail loud if it is down.
    // 2g is ample for one in-process DistributedQueryRunner + the live suites (3g was
    // trino-ducklake heritage); a smaller test JVM also keeps host free memory above the
    // Doris BE's low-water mark (~1.3GB) when other builds run concurrently — below it the
    // BE fails ALL queries with MEM_ALLOC_FAILED regardless of its own usage.
    maxHeapSize = "1536m"

    // Same JVM flags the in-process Trino 484 engine needs in trino-ducklake's test task
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
val pluginDirectory = layout.buildDirectory.dir("trino-plugin/trino-doris-$version")
val pluginAssemble by tasks.registering(Sync::class) {
    dependsOn(tasks.jar, verifyTrinoCompatibility)
    into(pluginDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    // Gradle can ignore newly added files in an output directory for ordinary
    // up-to-date checks. Force reconciliation when its inventory has drifted.
    outputs.upToDateWhen {
        val expected = (configurations.runtimeClasspath.get().files + tasks.jar.get().archiveFile.get().asFile)
            .map { it.name }.toSet()
        pluginDirectory.get().asFile.listFiles().orEmpty().map { it.name }.toSet() == expected
    }
}

// G12 guard mirroring the assembly-proof check: the assembled plugin dir must not bundle any
// engine-provided (parent-first SPI) jar, must bundle base-jdbc + Connector/J, and must carry
// exactly one unshaded Airlift guice jar.
val verifyPluginAssembly by tasks.registering {
    dependsOn(pluginAssemble)
    val pluginDir = pluginDirectory
    doLast {
        val inputs = configurations.runtimeClasspath.get().files + tasks.jar.get().archiveFile.get().asFile
        val expectedNames = inputs.map { it.name }.sorted()
        check(expectedNames.distinct().size == inputs.size) { "Duplicate assembly input filenames: $expectedNames" }
        val assembled = pluginDir.get().asFile.listFiles().orEmpty()
        val jars = assembled.map { it.name }.sorted()
        check(jars == expectedNames && assembled.all { it.isFile && it.extension == "jar" }) {
            "Plugin inventory differs from resolved inputs; expected $expectedNames, got $jars"
        }
        for (input in inputs) {
            check(Files.mismatch(input.toPath(), pluginDir.get().file(input.name).asFile.toPath()) == -1L) {
                "Plugin JAR differs from resolved input: ${input.name}"
            }
        }
        check(jars.isNotEmpty()) { "plugin dir is empty: ${pluginDir.get()}" }

        val forbiddenModules = listOf(
            "trino-spi",
            "trino-json",
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
        )
        val missing = requiredPrefixes.filter { prefix -> jars.none { it.startsWith(prefix) } }
        check(missing.isEmpty()) { "expected bundled jars missing (prefixes): $missing; got $jars" }

        val guiceJars = jars.filter { it.startsWith("guice-") }
        check(guiceJars.size == 1 && Regex("^guice-\\d+\\.jar$").matches(guiceJars.single())) {
            "expected exactly one unshaded io.airlift:guice jar, got: $guiceJars"
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

// A machine-readable path from the SAME verified build; never choose the first
// versioned directory on disk. Always rewrite it after successful verification.
tasks.register("prepareManualPlugin") {
    dependsOn(verifyPluginAssembly)
    doLast {
        layout.buildDirectory.file("manual-plugin-path.txt").get().asFile
            .writeText(pluginDirectory.get().asFile.absolutePath + "\n")
    }
}

tasks.build {
    dependsOn(pluginAssemble)
    dependsOn(verifyPluginAssembly)
}
