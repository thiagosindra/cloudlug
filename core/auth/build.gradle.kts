plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("cloudlug.android-common")
}

android {
    namespace = "dev.thiagosindra.cloudlug.auth"
}

// The Android half of §8.1: the Custom Tab, the redirect, and the plumbing that
// joins a completed grant to §8.3's credential store and §12.4's account row.
//
// The OAuth values themselves — PKCE, the authorize URL, the token exchange —
// are not here. They are in :providers:dropbox, where they are pure Kotlin and
// where tools/dropbox-auth runs the same code from a terminal.
dependencies {
    api(project(":providers:api"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    // §7 falls back to §5's declared capabilities where no connector exists.
    implementation(project(":core:transfer"))
    implementation(project(":core:security"))
    implementation(project(":providers:dropbox"))
    implementation(libs.okhttp)
    implementation(libs.appauth)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":providers:fake"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
