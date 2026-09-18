plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.newtransfer" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:transfer"))
    implementation(project(":providers:api"))
}
