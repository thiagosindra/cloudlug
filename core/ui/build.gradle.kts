plugins {
    id("cloudlug.android-feature")
}

android {
    namespace = "dev.thiagosindra.cloudlug.ui"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
}

/*
 * §24.1 and §24.3 label a transfer's two ends here, and since v0.4 that label
 * has to disambiguate two accounts at one provider. It is string arithmetic
 * over persisted rows, so it is checked on the JVM rather than on a device.
 */
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
