plugins {
    id("cloudlug.jvm-module")
}

/*
 * Run by hand, once, to mint the refresh token the live contract tests use.
 * Never part of `build` or CI: it is interactive and it touches a real account.
 *
 * It depends on :providers:dropbox rather than reimplementing the flow, so the
 * PKCE the app relies on is the PKCE a human has already driven end to end. A
 * second implementation here would prove nothing about the first.
 */
dependencies {
    implementation(project(":providers:dropbox"))
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<JavaExec>("dropboxAuth") {
    group = "verification"
    description = "Mints a Dropbox refresh token via PKCE, for DROPBOX_REFRESH_TOKEN (spec §8.1, §8.3)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.auth.DropboxAuthKt")
    classpath = sourceSets["main"].runtimeClasspath

    // It reads a pasted code from the terminal.
    standardInput = System.`in`
    outputs.upToDateWhen { false }
}
