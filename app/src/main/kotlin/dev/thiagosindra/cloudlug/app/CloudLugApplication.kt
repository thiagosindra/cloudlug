package dev.thiagosindra.cloudlug.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.thiagosindra.cloudlug.app.crash.crashReporter
import dev.thiagosindra.cloudlug.scheduling.SchedulerJobIds
import javax.inject.Inject

/**
 * Also WorkManager's [Configuration.Provider], because §17's worker is built by
 * the Hilt graph.
 *
 * A `@HiltWorker` cannot be constructed by WorkManager's default factory — it
 * has constructor dependencies the platform knows nothing about — so the
 * default initializer is removed in the manifest and WorkManager is configured
 * from here instead.
 */
@HiltAndroidApp
class CloudLugApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // WorkManager and §17's UIDT scheduler share JobScheduler's id
            // space, and an overlap would have one silently cancel the other's
            // job. Pinning the range here is what lets UidtTransferScheduler
            // guarantee its own ids sit above it.
            .setJobSchedulerJobIdRange(0, SchedulerJobIds.WORK_MANAGER_LAST)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else can fail. In release this is a no-op
        // object — see CrashReporter.
        crashReporter(this).install()
    }
}
