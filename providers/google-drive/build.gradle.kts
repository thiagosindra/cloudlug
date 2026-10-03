plugins {
    id("cloudlug.jvm-module")
}

dependencies {
    api(project(":providers:api"))
    implementation(project(":core:network"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":providers:fake"))
    testImplementation(testFixtures(project(":providers:fake")))
    testImplementation(project(":core:transfer"))
    testImplementation(project(":core:database"))
    testImplementation(project(":core:storage"))
    // The live tests refresh with the Desktop tooling client the token was minted with.
    testImplementation(project(":tools:drive-common"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

/*
 * The live tests read these, wired through explicitly because a Gradle test
 * JVM inherits the daemon's environment rather than the shell's (see
 * :providers:dropbox). Only the values' hashes become build inputs, and
 * nothing prints them.
 */
tasks.withType<Test>().configureEach {
    listOf("DRIVE_REFRESH_TOKEN", "DRIVE_TOOL_CLIENT_SECRET", "DRIVE_TEST_ROOT").forEach {
        environment(it, providers.environmentVariable(it).getOrElse(""))
    }
}
