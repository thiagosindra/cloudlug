plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("cloudlug.android-common")
}

android {
    namespace = "dev.thiagosindra.cloudlug.security"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// Android, not JVM, because the keys live in Android Keystore (§8.3) and
// Keystore has no JVM equivalent — the whole point is hardware-backed storage
// the process cannot export.
dependencies {
    implementation(project(":core:model"))

    // Keystore only exists on a device, so this module's tests are
    // instrumented. A JVM test here could only exercise a fake, which would
    // verify nothing about the thing §8.3 actually asks for.
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
