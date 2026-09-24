plugins { id("cloudlug.android-feature") }
android { namespace = "dev.thiagosindra.cloudlug.feature.transferdetails" }
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:transfer"))
    // §24.2 step 6 and §24.3's controls hand the transfer to the
    // platform rather than running it in app scope (§17).
    implementation(project(":core:scheduling"))
    // §24.1 and §24.3 name a transfer's ends by account when both are at
    // one provider, and the account rows of §12.4 live here.
    implementation(project(":core:auth"))
}
