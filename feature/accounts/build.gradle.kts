plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.accounts" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:auth"))
    implementation(project(":core:transfer"))
    implementation(project(":core:model"))
    implementation(project(":providers:api"))
    // The §8.1 sign-in is an activity result: the Custom Tab returns the
    // redirect through it.
    implementation(libs.androidx.activity.compose)
}
