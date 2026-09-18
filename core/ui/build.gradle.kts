plugins {
    id("cloudlug.android-feature")
}

android {
    namespace = "dev.thiagosindra.cloudlug.ui"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
}
