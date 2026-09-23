plugins {
    id("cloudlug.jvm-module")
}

/*
 * Captures the responses the offline tests run against (docs/testing.md rule 1).
 *
 * Run by hand against the scratch account, never in CI and never part of
 * `build`: it needs a live token, the network, and permission to create and
 * delete objects. It depends on :providers:dropbox so the refresh flow it uses
 * is the app's own (§8.3) rather than a second implementation that could agree
 * with itself while both are wrong.
 */
dependencies {
    implementation(project(":providers:dropbox"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}

tasks.register<JavaExec>("captureDropboxFixtures") {
    group = "verification"
    description = "Records verbatim Dropbox responses as test fixtures (docs/testing.md rule 1)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.capture.DropboxCaptureKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Read inside the process, never a Gradle property: a property lands in the
    // configuration cache and the daemon log (§26).
    environment("DROPBOX_REFRESH_TOKEN", providers.environmentVariable("DROPBOX_REFRESH_TOKEN").getOrElse(""))
    environment("DROPBOX_TEST_ROOT", providers.environmentVariable("DROPBOX_TEST_ROOT").getOrElse(""))

    // Where the fixtures land. The tests read the same directory.
    systemProperty(
        "cloudlug.fixtures.dir",
        rootProject.layout.projectDirectory.dir("providers/dropbox/src/test/resources/fixtures").asFile.path,
    )

    // Talking to a live account is never up to date.
    outputs.upToDateWhen { false }
}
