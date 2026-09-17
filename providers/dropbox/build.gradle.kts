plugins {
    id("cloudlug.jvm-module")
}

// Stub module — no adapter code in v0.1. See docs/status.md.
dependencies {
    api(project(":providers:api"))

    testImplementation(project(":providers:fake"))
}
