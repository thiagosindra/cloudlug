package dev.thiagosindra.cloudlug.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.thiagosindra.cloudlug.app.crash.crashReporter

@HiltAndroidApp
class CloudLugApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else can fail. In release this is a no-op
        // object — see CrashReporter.
        crashReporter(this).install()
    }
}
