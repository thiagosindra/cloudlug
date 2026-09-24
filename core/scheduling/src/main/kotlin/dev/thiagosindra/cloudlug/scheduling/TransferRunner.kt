package dev.thiagosindra.cloudlug.scheduling

import android.app.Notification
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.policy.NetworkPolicyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val networkMonitor: NetworkMonitor,
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
            // NonCancellable because pause *is* cancellation (§22.1): without
            // it the suspending read below would throw and the paused
            // transfer's notification would be left on screen claiming to be
            // running.
            withContext(NonCancellable) {
                repository.findTransfer(id)?.let(notifications::publishParked)
            }
        }
    }

    /**
     * The notification a job must already hold before it starts.
     *
     * Two callers, for two different platform demands. A user-initiated job on
     * API 34+ must post one from the instant it runs. And WorkManager runs an
     * *expedited* request as a foreground service on API 30 and below, so it
     * asks the worker for this before `doWork` is ever entered.
     */
    suspend fun startingNotification(id: TransferId): Notification {
        notifications.ensureChannel()
        return notifications.build(transferNow(id), null)
    }

    /**
     * Records that the **platform**, not the engine, stopped [id] for want of
     * an allowed network (§16, §24.4).
     *
     * Both schedulers enforce §16 as a constraint, and both enforce it by
     * killing the job: WorkManager cancels the worker's coroutine, the platform
     * calls `onStopJob`. The engine sees an ordinary cancellation — the same
     * thing a pause looks like — unwinds, and leaves the row saying RUNNING
     * with nothing running. §24.3 then shows "Running" and §24.4 shows a
     * filename, for a transfer that has stopped and will not start again until
     * a constraint the user cannot see is met.
     *
     * Guarded by §16's own gate rather than by a stop reason: a worker is
     * stopped for plenty of reasons that are not this one, and API 30 does not
     * report which. Asking the network directly is both simpler and true.
     */
    suspend fun parkForNetwork(id: TransferId) = withContext(NonCancellable) {
        val transfer = repository.findTransfer(id) ?: return@withContext
        if (transfer.status != TransferStatus.RUNNING) return@withContext
        val hold = NetworkPolicyGate.holdStatusFor(transfer.networkPolicy, networkMonitor.current())
            ?: return@withContext
        notifications.publishParked(
            repository.transitionTransfer(
                id,
                hold,
                errorCode = "network_constraint",
                errorMessage = "the transfer is waiting for a network its policy allows",
            ),
        )
    }

    private suspend fun transferNow(id: TransferId) =
        repository.findTransfer(id) ?: error("No transfer $id")
}
