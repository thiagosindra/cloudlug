plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.transferdetails" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:transfer"))
}
