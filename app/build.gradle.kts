import dev.thiagosindra.cloudlug.buildlogic.DebugSigningReport
import dev.thiagosindra.cloudlug.buildlogic.VerifyDebugFingerprint

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
        versionCode = 5
        versionName = "0.3.2"
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

    signingConfigs {
        // Committed on purpose, and the only signing material that ever will
        // be. A Google OAuth client for Android is keyed on the package name
        // *and* the signing certificate's SHA-1, so a debug key that differs
        // per machine means every contributor and CI each need their own OAuth
        // client, and a sign-in that works on one laptop fails on the next
        // with an error that names neither cause. One shared debug key makes
        // §8.1 reproducible.
        //
        // It protects nothing: it signs debug builds only, its password is the
        // Android default, and the platform's own default debug key is equally
        // public. The release key is not here and never will be — see
        // docs/oauth.md and CONTRIBUTING.md.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // No shrinking yet: v0.6 is the security-review milestone (§33) and
            // the keep rules belong with the OkHttp and OAuth code that needs
            // them. No release signing config: a release build is signed by
            // whoever ships it, with a key that is not in this repository.
            isMinifyEnabled = false
        }
    }
}

private val debugKeystore = layout.projectDirectory.file("debug.keystore")

val debugSigningReport = tasks.register<DebugSigningReport>("debugSigningReport") {
    group = "verification"
    description = "Prints the committed debug certificate's SHA-1 (docs/oauth.md)."
    keystore.set(debugKeystore)
    storePassword.set("android")
    keyAlias.set("androiddebugkey")
    fingerprintFile.set(layout.buildDirectory.file("signing/debug-sha1.txt"))
    // Always: the value is the point of the task, and it costs milliseconds.
    outputs.upToDateWhen { false }
}

val verifyDebugFingerprint = tasks.register<VerifyDebugFingerprint>("verifyDebugFingerprint") {
    group = "verification"
    description = "Fails if docs/oauth.md no longer names the committed debug certificate."
    keystore.set(debugKeystore)
    storePassword.set("android")
    keyAlias.set("androiddebugkey")
    documentation.set(rootProject.layout.projectDirectory.file("docs/oauth.md"))
}

tasks.named("check") { dependsOn(verifyDebugFingerprint) }

// `matching`, not `named`: AGP registers assembleDebug while creating variants,
// which is after this script is evaluated.
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(debugSigningReport) }

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:transfer"))
    // §17: the two platform schedulers and the §24.4 notification.
    implementation(project(":core:scheduling"))
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
    // Both declared explicitly because this module's manifest and themes name
    // them by hand: the manifest merges a theme onto AppAuth's
    // RedirectUriReceiverActivity, and that theme descends from
    // Theme.AppCompat. Each arrives transitively through :core:auth, but a
    // class or style a module references by name belongs to a dependency that
    // module declares — and lint says so (MissingClass) rather than leaving it
    // to be discovered when the transitive changes.
    implementation(libs.androidx.appcompat)
    implementation(libs.appauth)
    implementation(libs.sqlite.framework)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    // The Application is WorkManager's Configuration.Provider, so the
    // worker factory is assembled here even though the worker is not.
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
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
