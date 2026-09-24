package dev.thiagosindra.cloudlug.scheduling

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.transfer.schedule.NetworkRequirement
import dev.thiagosindra.cloudlug.transfer.schedule.SchedulingPolicy
import javax.inject.Inject

/**
 * §17 on API 26–33: a long-running worker with a `dataSync` foreground service.
 *
 * **Android 14 caps `dataSync` at roughly six hours per 24-hour window**, which
 * is why the UIDT path above exists at all. This one is for the versions that
 * have no UIDT, where that cap does not apply.
 *
 * Correctness never depends on the worker staying alive (§17): if the platform
 * stops it, the rows are still right and the next reconcile picks the transfer
 * back up.
 */
class WorkManagerTransferScheduler @Inject constructor(
    private val work: WorkManager,
    private val repository: TransferRepository,
) : TransferScheduler {

    override suspend fun enqueue(id: TransferId) {
        val transfer = repository.findTransfer(id) ?: return
        // isStartable, not isEnqueueable: enqueue() is an instruction — the
        // user pressed Start or Resume — and must be able to lift a pause.
        // reconcile() below is the automatic sweep and uses the stricter rule.
        if (!SchedulingPolicy.isStartable(transfer)) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when (SchedulingPolicy.networkFor(transfer)) {
                    NetworkRequirement.UNMETERED -> NetworkType.UNMETERED
                    NetworkRequirement.ANY_CONNECTED -> NetworkType.CONNECTED
                },
            )
            .build()

        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(TransferWorker.KEY_TRANSFER_ID, id.value).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        // KEEP, not REPLACE: enqueueing a transfer that is already running must
        // not restart it. Reconciliation calls this for everything with work
        // left, including the transfer currently in flight.
        work.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun cancel(id: TransferId) {
        work.cancelUniqueWork(workName(id))
    }

    override suspend fun reconcile() {
        SchedulingPolicy.toEnqueue(repository.listTransfers()).forEach { enqueue(it.id) }
    }

    private fun workName(id: TransferId) = "cloudlug-transfer-${id.value}"
}
