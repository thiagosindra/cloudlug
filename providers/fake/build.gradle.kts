plugins {
    id("cloudlug.jvm-module")
}

// FakeCloudProvider (spec §31.3) and the provider contract suite (spec §31.2).
// This is a main-source-set artifact, not test-only, so that every future
// adapter module can depend on it to run the same contract tests.
dependencies {
    api(project(":providers:api"))
    api(project(":core:hashing"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    // The contract suite ships in the main source set so every adapter module
    // can run it, which means the test framework is an api dependency here.
    api(kotlin("test-junit5"))
    api(libs.junit.jupiter)

    testImplementation(libs.kotlinx.coroutines.test)
}
