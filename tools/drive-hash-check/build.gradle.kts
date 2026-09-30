plugins {
    id("cloudlug.jvm-module")
}

/*
 * §36: validate Drive's md5Checksum / sha256Checksum against :core:hashing on
 * real uploads, before any adapter code depends on them. Deliberately not a
 * test: it needs a live token and the network, so it never runs in CI or as
 * part of `build`.
 */
dependencies {
    implementation(project(":tools:drive-common"))
    implementation(project(":core:hashing"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<JavaExec>("validateDriveHashes") {
    group = "verification"
    description = "Compares Drive's md5Checksum and sha256Checksum against core:hashing for real uploads (spec §36)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.drivehash.DriveHashCheckKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Read inside the process, never a Gradle property (§26).
    listOf("DRIVE_REFRESH_TOKEN", "DRIVE_TOOL_CLIENT_SECRET", "DRIVE_TEST_ROOT", "DRIVE_HASH_CHECK_FILE").forEach {
        environment(it, providers.environmentVariable(it).getOrElse(""))
    }
    outputs.upToDateWhen { false }
}
