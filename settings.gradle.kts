pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cloudlug"

// v0.1 is JVM-only: every module below builds and tests with `./gradlew test`
// and needs no Android SDK. The Android modules from spec §4 (:app, :core:ui,
// feature/*) arrive in v0.2 — see docs/decisions.md ADR-0003.
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
