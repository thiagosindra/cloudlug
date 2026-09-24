package dev.thiagosindra.cloudlug.scheduling

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/** Where a §17 WorkManager transfer actually runs, on API 26–33. */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val runner: TransferRunner,
    private val notifications: TransferNotifications,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = coroutineScope {
        val raw = inputData.getString(KEY_TRANSFER_ID) ?: return@coroutineScope Result.failure()
        val id = TransferId(raw)

        val status = try {
            runner.run(id, this) { notification -> setForeground(foreground(id, notification)) }
        } catch (stopped: CancellationException) {
            // WorkManager enforces the §16 constraint by cancelling the worker,
            // which the engine cannot tell from a pause. Say what happened
            // before unwinding; WorkManager re-enqueues constraint-stopped work
            // itself, so there is no Result to return here.
            runner.parkForNetwork(id)
            throw stopped
        }

        // "The transfer did not complete" is not a worker failure: it is a row
        // in a state the next reconcile will look at, and asking WorkManager to
        // retry an *error* would put a second policy on top of §23's, with its
        // own backoff and its own opinions.
        //
        // WAITING_FOR_WIFI is the exception, and it is not an error at all. It
        // is this request's own network constraint saying no, so a retry is the
        // platform re-applying that constraint — §16's "resumes automatically"
        // with nothing in the app watching connectivity. Returning success here
        // finished the work instead, and the transfer sat parked until someone
        // opened the app. The other two waiting states clear when the user does
        // something (§13.1), not when a constraint does.
        if (status == TransferStatus.WAITING_FOR_WIFI) Result.retry() else Result.success()
    }

    /**
     * `dataSync` is the type §17 names, and from API 29 the type has to be
     * declared at the call as well as in the manifest.
     */
    private fun foreground(id: TransferId, notification: android.app.Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notifications.notificationId(id),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notifications.notificationId(id), notification)
        }

    internal companion object {
        const val KEY_TRANSFER_ID = "transferId"
    }
}
