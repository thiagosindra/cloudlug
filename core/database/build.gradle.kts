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
    // The bundled driver ships SQLite for both the JVM and Android ABIs, so the
    // tests and the app run the same engine — ADR-0021.
    api(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
}
