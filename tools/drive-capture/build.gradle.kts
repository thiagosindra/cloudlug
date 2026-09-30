plugins {
    id("cloudlug.jvm-module")
}

/*
 * Captures the Drive responses the offline tests run against (docs/testing.md
 * rule 1). Run by hand against the scratch account, never in CI and never
 * part of `build`: it needs a live token, the network, and permission to
 * create and delete objects beneath DRIVE_TEST_ROOT.
 */
dependencies {
    implementation(project(":tools:drive-common"))
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<JavaExec>("captureDriveFixtures") {
    group = "verification"
    description = "Records verbatim Google Drive responses as test fixtures (docs/testing.md rule 1)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.drivecapture.DriveCaptureKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Read inside the process, never a Gradle property (§26).
    listOf("DRIVE_REFRESH_TOKEN", "DRIVE_TOOL_CLIENT_SECRET", "DRIVE_TEST_ROOT").forEach {
        environment(it, providers.environmentVariable(it).getOrElse(""))
    }

    // Where the fixtures land. The adapter's tests will read the same directory.
    systemProperty(
        "cloudlug.fixtures.dir",
        rootProject.layout.projectDirectory.dir("providers/google-drive/src/test/resources/fixtures").asFile.path,
    )
    outputs.upToDateWhen { false }
}
