plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.newtransfer" }
dependencies {
    implementation(project(":core:ui"))
    // §24.2 step 1 offers connected accounts now, which live here (§12.4).
    implementation(project(":core:auth"))
    implementation(project(":core:transfer"))
    implementation(project(":providers:api"))
}
