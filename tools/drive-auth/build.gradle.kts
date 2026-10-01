plugins {
    id("cloudlug.jvm-module")
}

/*
 * Run by hand, once per scratch account (and again every seven days while the
 * OAuth app is in Testing status), to mint DRIVE_REFRESH_TOKEN. Never part of
 * `build` or CI: it opens a browser and touches a real account.
 */
dependencies {
    implementation(project(":tools:drive-common"))
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<JavaExec>("driveAuth") {
    group = "verification"
    description = "Mints a Google Drive refresh token via PKCE and a loopback redirect, for DRIVE_REFRESH_TOKEN (spec §8.2, §8.3)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.driveauth.DriveAuthKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Read inside the process, never a Gradle property: a property lands in the
    // configuration cache and the daemon log (§26).
    environment("DRIVE_TOOL_CLIENT_SECRET", providers.environmentVariable("DRIVE_TOOL_CLIENT_SECRET").getOrElse(""))
    outputs.upToDateWhen { false }
}
