plugins {
    id("cloudlug.jvm-module")
}

/*
 * What the three Drive tools share: the Desktop client, the token refresh, the
 * test-root guard and the resumable-upload calls. Not shipped, never in the app.
 *
 * It depends on :providers:google-drive for GoogleOAuth, so the forms a person
 * drives by hand are the forms the app will send — the same reason the Dropbox
 * tools depend on :providers:dropbox.
 */
dependencies {
    api(project(":providers:google-drive"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
