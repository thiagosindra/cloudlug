plugins {
    id("cloudlug.jvm-module")
}

// The HTTP stack every adapter shares, so §26's redaction is one interceptor
// rather than discipline repeated per call site.
dependencies {
    api(libs.okhttp)
    implementation(project(":core:model"))

    testImplementation(libs.okhttp.mockwebserver)
}
