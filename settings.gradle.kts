pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Resolves and downloads the JDK 25 toolchain on demand (jvmToolchain(25))
    // from the Foojay Disco API, so the build doesn't depend on a matching local JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "trino-doris-connector"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
