package dev.thiagosindra.cloudlug.scheduling

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import dev.thiagosindra.cloudlug.model.TransferId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a §17 UIDT job actually runs. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class TransferJobService : JobService() {

    @Inject lateinit var runner: TransferRunner

    @Inject lateinit var notifications: TransferNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val id = TransferId(params.extras.getString(UidtTransferScheduler.EXTRA_TRANSFER_ID) ?: return false)
        work = scope.launch {
            runner.run(id, this) { notification ->
                // Mandatory for a user-initiated job, and the same call is how
                // progress is updated. DETACH so a finished transfer's last
                // notification survives the job ending rather than vanishing
                // at the moment it has something to say.
                setNotification(
                    params,
                    notifications.notificationId(id),
                    notification,
                    JOB_END_NOTIFICATION_POLICY_DETACH,
                )
            }
            jobFinished(params, false)
        }
        return true
    }

    /**
     * The platform is taking the job away — its constraints stopped being met,
     * or the system needs the resources.
     *
     * Returning true asks for a reschedule. Nothing is lost either way: the
     * engine has persisted everything it needs, and the transfer resumes from
     * the database (§2.4).
     */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        val id = params.extras.getString(UidtTransferScheduler.EXTRA_TRANSFER_ID)
        if (id != null) {
            // Says why, when the why is §16 — see TransferRunner.parkForNetwork,
            // which checks rather than assumes. goAsync has no equivalent here,
            // so this is best-effort against the process outliving the job; the
            // next reconcile settles it either way (§2.4).
            scope.launch { runner.parkForNetwork(TransferId(id)) }
        }
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
