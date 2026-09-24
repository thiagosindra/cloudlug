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

        runner.run(id, this) { notification -> setForeground(foreground(id, notification)) }

        // Always success: "the transfer did not complete" is not a worker
        // failure, it is a row in a state the next reconcile will look at.
        // Asking WorkManager to retry would put a second policy on top of
        // §23's, with its own backoff and its own opinions.
        Result.success()
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
