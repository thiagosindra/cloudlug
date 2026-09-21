plugins {
    id("cloudlug.jvm-module")
}

/*
 * §36's first item: "Validate `content_hash` ... against provider-reported
 * values on real uploads ... the first hour of each adapter milestone, not the
 * last." The v0.1 vectors were derived from the algorithm definition, which
 * catches an implementation bug but not a shared misreading of the spec. Only a
 * real account can tell those apart.
 *
 * Deliberately not a test: it needs a live token and the network, so it must
 * never run in CI or as part of `build`.
 */
dependencies {
    implementation(project(":core:hashing"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<JavaExec>("validateDropboxHashes") {
    group = "verification"
    description = "Compares Dropbox's content_hash against core:hashing for real uploads (spec §36)."
    mainClass.set("dev.thiagosindra.cloudlug.tools.dropbox.DropboxHashCheckKt")
    classpath = sourceSets["main"].runtimeClasspath

    // The token is read from the environment inside the process. It is never a
    // Gradle property, never a task input, and never written to a file: a
    // property would land in the configuration cache and the daemon log.
    environment("DROPBOX_TOKEN", providers.environmentVariable("DROPBOX_TOKEN").getOrElse(""))
    environment("DROPBOX_TEST_PATH", providers.environmentVariable("DROPBOX_TEST_PATH").getOrElse(""))

    // Talking to a live account is never up to date.
    outputs.upToDateWhen { false }
}
