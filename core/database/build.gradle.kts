plugins {
    id("cloudlug.jvm-module")
    // Versions come from buildSrc, which also puts these on the classpath for
    // the Android convention plugins; re-declaring one here would clash.
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Spec §33 v0.2 and docs/decisions.md ADR-0002: Room behind the DAO interfaces.
// exportSchema is on and the JSON is committed, so a schema change shows up in
// review as a diff rather than as a migration discovered on a user's device.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.room.runtime)
    ksp(libs.room.compiler)

    // The bundled driver is a *test* dependency. It was `api` in v0.2, which put
    // a desktop SQLite and the JVM-only builders on the APK's classpath; the
    // app now uses Room's Android builder and the platform driver instead
    // (ADR-0021 as revised, ADR-0025).
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.kotlinx.coroutines.test)
}
