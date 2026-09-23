plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("cloudlug.android-common")
}

/*
 * A test harness that survives killing CloudLug.
 *
 * §31.4's first four scenarios all begin "kill the process", and CloudLug's own
 * `androidTest` cannot do it: instrumentation is loaded into the process of the
 * package it targets, so `am force-stop dev.thiagosindra.cloudlug` from
 * `:app`'s tests kills the test along with the app and the run reports a crash
 * rather than a result.
 *
 * So this module is an empty application that instruments *itself*, and drives
 * CloudLug from outside through UiAutomator. Force-stopping CloudLug is then
 * an ordinary thing that happens to another package, and the assertions about
 * what CloudLug does next run in a process that was never touched.
 *
 * Not shipped: it has no source beyond a manifest, and nothing depends on it.
 */
android {
    namespace = "dev.thiagosindra.cloudlug.recovery"

    defaultConfig {
        applicationId = "dev.thiagosindra.cloudlug.recovery"
        // Targets itself. That single line is the reason this module exists.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
