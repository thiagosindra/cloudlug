package dev.thiagosindra.cloudlug.scheduling

import android.app.Notification
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.transfer.TransferController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One transfer, run inside whatever the platform gave us, with the §24.4
 * notification kept current while it runs.
 *
 * Shared by both schedulers on purpose. The UIDT job and the WorkManager worker
 * differ in how the platform keeps the process alive and in nothing else; a
 * second copy of "run it and show progress" is where the two would drift.
 */
@Singleton
class TransferRunner @Inject constructor(
    private val controller: TransferController,
    private val repository: TransferRepository,
    private val notifications: TransferNotifications,
) {

    /**
     * Runs [id] to completion or to a parked state, publishing progress through
     * [publish] as it goes.
     *
     * [publish] is how the two platforms differ: a JobService calls
     * `setNotification`, a worker calls `setForeground`. Neither is this
     * class's business.
     */
    suspend fun run(id: TransferId, scope: CoroutineScope, publish: suspend (Notification) -> Unit): TransferStatus {
        notifications.ensureChannel()

        // Progress comes from the database rather than from the engine, for the
        // same reason §24.3 does: the rows are authoritative (§2.4), so what
        // the notification shows is what a screen would show, and a notification
        // that outlived its runner would still have been telling the truth.
        val updates = scope.launch {
            combine(
                repository.observeTransfer(id).filterNotNull(),
                repository.observeItems(id),
            ) { transfer, items -> transfer to items.firstOrNull { item -> item.status.isActive } }
                .distinctUntilChanged()
                .collect { (transfer, current) -> publish(notifications.build(transfer, current)) }
        }

        return try {
            // Post once before the first row changes, so the job has a
            // notification from the instant it starts — API 34+ requires one.
            publish(notifications.build(transferNow(id), null))
            controller.run(id)
        } finally {
            updates.cancel()
        }
    }

    private suspend fun transferNow(id: TransferId) =
        repository.findTransfer(id) ?: error("No transfer $id")
}
