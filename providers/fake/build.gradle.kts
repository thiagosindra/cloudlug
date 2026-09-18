plugins {
    id("cloudlug.jvm-module")
    `java-test-fixtures`
}

// FakeCloudProvider (spec §31.3) in the main source set, and the §31.2 provider
// contract suite in test fixtures.
//
// The contract suite was in `main` so that every adapter module could subclass
// it. That put JUnit — and method names containing spaces — into a runtime
// artifact, which D8 refuses to dex below DEX 040 and which would have shipped
// test scaffolding inside the APK. Test fixtures give adapters the same reuse
// without either problem: `testImplementation(testFixtures(project(...)))`.
dependencies {
    api(project(":providers:api"))
    api(project(":core:hashing"))
    api(libs.kotlinx.coroutines.core)

    testFixturesApi(project(":providers:api"))
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(libs.junit.jupiter)

    testImplementation(testFixtures(project(":providers:fake")))
    testImplementation(libs.kotlinx.coroutines.test)
}
