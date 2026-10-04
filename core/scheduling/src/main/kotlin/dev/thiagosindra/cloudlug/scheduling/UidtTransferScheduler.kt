package dev.thiagosindra.cloudlug.scheduling

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.transfer.schedule.NetworkRequirement
import dev.thiagosindra.cloudlug.transfer.schedule.PlatformJobState
import dev.thiagosindra.cloudlug.transfer.schedule.SchedulingPolicy
import javax.inject.Inject

/**
 * §17 on API 34+: a User-Initiated Data Transfer job.
 *
 * UIDT exists for exactly this case. Android 14 caps a `dataSync` foreground
 * service at roughly six hours per 24, which a multi-hundred-gigabyte
 * migration will exceed; a UIDT job has no such quota and is started
 * immediately once its constraints are met.
 *
 * Three things the platform insists on, all of them checked in `JobInfo`'s own
 * validation rather than documented anywhere convenient: the job must be
 * `PRIORITY_MAX` (the builder does this itself), it must declare a network, and
 * it must show a notification while running. What it does *not* forbid is
 * persistence — nothing in the user-initiated branch of `enforceValidity`
 * mentions it — so these are persisted and a reboot should find them restored.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UidtTransferScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: TransferRepository,
    private val notifications: TransferNotifications,
) : TransferScheduler {

    private val jobs = context.getSystemService(JobScheduler::class.java)

    override suspend fun enqueue(id: TransferId) {
        val transfer = repository.findTransfer(id) ?: return
        // isStartable, not isEnqueueable: enqueue() is an instruction — the
        // user pressed Start or Resume — and must be able to lift a pause.
        // reconcile() below is the automatic sweep and uses the stricter rule.
        if (!SchedulingPolicy.isStartable(transfer)) return
        // Scheduling an id the platform is running stops that job and starts
        // a new one beside it, before the old one has let go of its chunk.
        if (!SchedulingPolicy.shouldSchedule(platformState(id))) return

        val network = when (SchedulingPolicy.networkFor(transfer)) {
            NetworkRequirement.UNMETERED -> JobInfo.NETWORK_TYPE_UNMETERED
            NetworkRequirement.ANY_CONNECTED -> JobInfo.NETWORK_TYPE_ANY
        }

        val info = JobInfo.Builder(jobId(id), ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            // Both directions: every byte is downloaded from the source and
            // uploaded to the destination, which is what makes this a transfer
            // rather than a download (§1).
            .setEstimatedNetworkBytes(transfer.totalBytes, transfer.totalBytes)
            .setRequiredNetworkType(network)
            // §2.4. A network *specifier* would be rejected on a persisted job;
            // a plain type, which is all §16 needs, is not.
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putString(EXTRA_TRANSFER_ID, id.value) })
            .build()

        // A UIDT job may only be scheduled while the app is visible or is
        // otherwise permitted to start an activity. That is never true from a
        // boot receiver, and saying so is better than a silent no-op: the user
        // opens the app and reconcile() picks it up.
        if (jobs.schedule(info) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "the platform refused a user-initiated job; it will be enqueued when the app is opened")
        }
    }

    override suspend fun cancel(id: TransferId) = jobs.cancel(jobId(id))

    override suspend fun reconcile() {
        SchedulingPolicy.reconcile(repository.listTransfers()) { transfer ->
            enqueue(transfer.id)
            // A job the platform holds runs nothing and posts nothing, so the
            // shade would otherwise say nothing about a transfer that is not
            // moving. This is where a battery saver shows up (§24.4).
            holdReason(transfer.id)?.let { notifications.publishHeld(transfer, it) }
        }
    }

    override suspend fun holdReason(id: TransferId): String? = when (jobs.getPendingJobReason(jobId(id))) {
        // Our job declares no battery or charging constraint, so a battery
        // saver or a low battery reaches us as the device's state.
        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "held by Android: battery saver or the phone's state"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "held by Android until the battery is not low"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "held by Android until the phone is charging"
        JobScheduler.PENDING_JOB_REASON_APP_STANDBY,
        JobScheduler.PENDING_JOB_REASON_QUOTA,
        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION,
        -> "held by Android: CloudLug's background work is being limited"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "waiting for a network this transfer allows"
        else -> null
    }

    private fun platformState(id: TransferId): PlatformJobState = when (jobs.getPendingJobReason(jobId(id))) {
        JobScheduler.PENDING_JOB_REASON_EXECUTING -> PlatformJobState.EXECUTING
        JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID -> PlatformJobState.NONE
        else -> PlatformJobState.WAITING
    }

    /**
     * A job id has to be an Int, has to be the same one for the same transfer
     * every time — or cancelling and re-enqueueing would leak jobs — and has to
     * stay out of WorkManager's range.
     *
     * That last part is not hypothetical. WorkManager schedules through
     * JobScheduler too, and it is initialised on every API level because the
     * Application supplies its configuration; a raw `hashCode()` can land on an
     * id WorkManager is already using, and whichever scheduled second would
     * silently cancel the other's job. `CloudLugApplication` pins WorkManager
     * to [WORK_MANAGER_JOB_IDS] and this maps above it.
     *
     * Two transfers could still collide inside this range. With ~900k slots and
     * a handful of live transfers that is remote, and the cost is one transfer
     * losing its job until the next reconcile, not lost data.
     */
    private fun jobId(id: TransferId) =
        SchedulerJobIds.FIRST_TRANSFER_JOB +
            (kotlin.math.abs(id.value.hashCode().toLong()) % SchedulerJobIds.TRANSFER_JOB_RANGE).toInt()

    internal companion object {
        const val EXTRA_TRANSFER_ID = "transferId"
        private const val TAG = "CloudLugScheduler"
    }
}
