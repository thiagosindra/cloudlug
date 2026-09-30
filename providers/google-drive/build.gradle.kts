plugins {
    id("cloudlug.jvm-module")
}

// v0.6 Step 1: only GoogleOAuth, which the Drive tools reuse. The adapter
// follows once real hashes match and fixtures exist (docs/testing.md).
dependencies {
    api(project(":providers:api"))

    testImplementation(project(":providers:fake"))
}
