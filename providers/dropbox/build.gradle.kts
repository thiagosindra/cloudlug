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
    testImplementation(libs.okhttp.mockwebserver)
}

/*
 * Live tests read these. They are wired through explicitly because a Gradle
 * test JVM inherits the *daemon's* environment, not the shell's: a token
 * exported after the daemon started is invisible, and the live tests would skip
 * while looking like they ran.
 *
 * Only the value's hash becomes a build input, never the value itself, and
 * nothing here prints them.
 */
tasks.withType<Test>().configureEach {
    environment("DROPBOX_REFRESH_TOKEN", providers.environmentVariable("DROPBOX_REFRESH_TOKEN").getOrElse(""))
    environment("DROPBOX_TEST_ROOT", providers.environmentVariable("DROPBOX_TEST_ROOT").getOrElse(""))
}
