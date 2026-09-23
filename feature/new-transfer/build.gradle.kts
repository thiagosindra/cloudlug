plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.newtransfer" }
dependencies {
    implementation(project(":core:ui"))
    // §24.2 step 1 offers connected accounts now, which live here (§12.4).
    implementation(project(":core:auth"))
    implementation(project(":core:transfer"))
    implementation(project(":providers:api"))
}

/*
 * §9's browser keeps its arithmetic — where the user is, and what path a
 * selection made there will land at — in `WizardState`, which is a pure data
 * class. The journey test covers it on a device, but a device is the slowest
 * and least available place to find out that a path is wrong, so it is also
 * checked here, where it runs on every `./gradlew test`.
 */
dependencies {
    // Same stack as the JVM modules (ADR-0003); an Android library's unit
    // test source set does not inherit it.
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
