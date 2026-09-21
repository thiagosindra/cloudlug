plugins {
    id("cloudlug.jvm-module")
}

dependencies {
    api(project(":providers:api"))
    implementation(project(":core:network"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":providers:fake"))
    testImplementation(testFixtures(project(":providers:fake")))
    testImplementation(libs.okhttp.mockwebserver)
}
