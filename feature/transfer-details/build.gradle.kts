plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.transferdetails" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:transfer"))
    // §24.1 and §24.3 name a transfer's ends by account when both are at
    // one provider, and the account rows of §12.4 live here.
    implementation(project(":core:auth"))
}
