package dev.thiagosindra.cloudlug.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.thiagosindra.cloudlug.BuildConfig
import dev.thiagosindra.cloudlug.app.crash.crashReporter
import dev.thiagosindra.cloudlug.app.di.DemoAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CloudLugApplication : Application() {

    @Inject
    lateinit var demoAccounts: DemoAccounts

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else can fail. In release this is a no-op
        // object — see CrashReporter.
        crashReporter(this).install()

        // §24.2 picks accounts now, so the demo provider needs a row to be
        // pickable at all. Debug only, and never a credential — see
        // DemoAccounts.
        if (BuildConfig.DEBUG) {
            CoroutineScope(Dispatchers.IO).launch { demoAccounts.seed() }
        }
    }
}
