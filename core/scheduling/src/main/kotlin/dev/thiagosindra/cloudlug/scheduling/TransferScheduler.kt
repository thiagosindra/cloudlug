package dev.thiagosindra.cloudlug.scheduling

import dev.thiagosindra.cloudlug.model.TransferId

/**
 * Hands a transfer to the platform to be run (spec §17).
 *
 * Two implementations, chosen by API level, and the difference between them is
 * the whole reason §17 exists: Android 14 caps a `dataSync` foreground service
 * at roughly six hours per day, which a multi-hundred-gigabyte migration will
 * exceed, so 34+ uses a User-Initiated Data Transfer job instead.
 *
 * Neither owns any state. The database is authoritative (spec §2.4) and a
 * worker is a disposable executor: [reconcile] rebuilds the whole schedule from
 * rows, which is what makes recovery after process death or reboot a matter of
 * re-reading rather than of remembering.
 */
interface TransferScheduler {

    /** Asks the platform to run [id] when its §16 network constraint is met. */
    suspend fun enqueue(id: TransferId)

    /** Stops the platform running [id]; the transfer's own state is not touched. */
    suspend fun cancel(id: TransferId)

    /**
     * Re-enqueues everything `SchedulingPolicy` says has work left.
     *
     * Called on boot and on app start. Idempotent by construction: enqueueing a
     * transfer the platform already holds replaces that job rather than adding
     * a second.
     */
    suspend fun reconcile()
}

/**
 * How JobScheduler's id space is divided.
 *
 * WorkManager schedules through JobScheduler too, and it is initialised on
 * every API level because the Application supplies its configuration. If a
 * user-initiated job landed on an id WorkManager was already using, whichever
 * scheduled second would silently cancel the other. So the Application pins
 * WorkManager to everything up to [WORK_MANAGER_LAST] and §17's own jobs take
 * ids above it.
 *
 * Lint names this hazard (`SpecifyJobSchedulerIdRange`) and was how it was
 * found — nothing in the two schedulers' own code hints at the overlap, because
 * neither of them is where it happens.
 */
object SchedulerJobIds {

    /** WorkManager's range is everything at or below this. */
    const val WORK_MANAGER_LAST: Int = 99_999

    internal const val FIRST_TRANSFER_JOB: Int = WORK_MANAGER_LAST + 1

    internal const val TRANSFER_JOB_RANGE: Long = 900_000L
}
