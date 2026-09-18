pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cloudlug"

// The engine modules are pure Kotlin/JVM and test with `./gradlew test` on any
// JDK. `:core:database` uses Room's KMP artifacts, so its Room tests run on the
// JVM too (docs/decisions.md ADR-0021) — only the modules below it need the
// Android SDK.
include(
    ":core:model",
    ":core:database",
    ":core:hashing",
    ":core:storage",
    ":core:transfer",
    ":providers:api",
    ":providers:fake",
    ":providers:dropbox",
    ":providers:google-drive",
)

// Not shipped. A §36 validation harness, run by hand against a real account:
// it needs a token and the network, so it is never part of `build` or CI.
include(":tools:dropbox-hash-check")

// Android modules from spec §4 and the §24 screens.
include(
    ":app",
    ":core:ui",
    ":feature:home",
    ":feature:new-transfer",
    ":feature:transfer-details",
)
