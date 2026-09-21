plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("cloudlug.android-common")
}

android {
    namespace = "dev.thiagosindra.cloudlug"

    defaultConfig {
        applicationId = "dev.thiagosindra.cloudlug"
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AppAuth's redirect receiver claims this scheme. It is the application
        // id, which is what stops another app on the device registering the
        // same one and intercepting §8.1's authorization code.
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.thiagosindra.cloudlug"
    }

    buildFeatures {
        compose = true
        // The demo provider is offered in debug builds only (§31.3).
        buildConfig = true
    }

    buildTypes {
        release {
            // No shrinking yet: v0.5 is the security-review milestone (§33) and
            // the keep rules belong with the OkHttp and OAuth code that needs
            // them. Debug signing only — no signing material is committed (§30).
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:transfer"))
    implementation(project(":providers:api"))
    implementation(project(":providers:dropbox"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:auth"))
    // Still here in v0.3, now as its own provider type rather than standing in
    // for Dropbox: it exercises the engine's whole surface with no OAuth and no
    // network (§31.3), which is what the emulator journey test needs. Debug
    // builds only.
    implementation(project(":providers:fake"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:home"))
    implementation(project(":feature:new-transfer"))
    implementation(project(":feature:transfer-details"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.sqlite.framework)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    // Hosts a ComponentActivity for createAndroidComposeRule; debug only.
    debugImplementation(libs.compose.ui.test.manifest)

    // The emulator smoke test (spec §31.4). It launches the real Application,
    // so it builds the real Hilt graph and opens the real database — which is
    // the whole point, and why it needs no Hilt test runner of its own.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
