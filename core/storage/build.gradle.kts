plugins {
    id("cloudlug.jvm-module")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
