// A feature/* or core:ui module (spec §4): an Android library with Compose and
// Hilt, and the shared engine modules on its classpath.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("cloudlug.android-common")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    buildFeatures { compose = true }
}

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(platform(lib("compose-bom")))
    "implementation"(lib("compose-ui"))
    "implementation"(lib("compose-ui-graphics"))
    "implementation"(lib("compose-ui-tooling-preview"))
    "implementation"(lib("compose-material3"))
    "debugImplementation"(lib("compose-ui-tooling"))

    "implementation"(lib("androidx-core-ktx"))
    "implementation"(lib("androidx-lifecycle-runtime-compose"))
    "implementation"(lib("androidx-lifecycle-viewmodel-compose"))
    "implementation"(lib("hilt-android"))
    "implementation"(lib("hilt-navigation-compose"))
    "ksp"(lib("hilt-compiler"))
}
