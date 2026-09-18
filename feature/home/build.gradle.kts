plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.home" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:transfer"))
}
