plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("cloudlug.android-common")
}

/*
 * §17's background execution: the two platform schedulers, the §24.4
 * notification, and the receivers that resume after reboot or act on a
 * notification button.
 *
 * Its own module rather than part of :app because the decision it implements —
 * which transfers run, and under what network — belongs with the engine, and
 * because :app should not be where a JobService lives. It is not a Compose
 * module: it draws a notification, not a screen.
 */
android {
    namespace = "dev.thiagosindra.cloudlug.scheduling"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:transfer"))
    // Labels and byte formatting for the §24.4 notification, so its wording
    // matches §24.1 and §24.3 rather than being invented a third time.
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
