plugins {
    id("cloudlug.jvm-module")
}

// The transfer engine depends on provider *abstractions* only. Adding a
// dependency on :providers:dropbox or :providers:google-drive here would
// violate spec §32.7 and is checked by ProviderNeutralityTest.
dependencies {
    api(project(":core:model"))
    api(project(":providers:api"))
    api(project(":core:database"))
    api(project(":core:hashing"))
    api(project(":core:storage"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(project(":providers:fake"))
    testImplementation(libs.kotlinx.coroutines.test)
}
