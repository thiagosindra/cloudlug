package dev.thiagosindra.cloudlug.transfer.schedule

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus

/**
 * What a platform scheduler must require of the network before it runs a
 * transfer (spec §16).
 *
 * Deliberately not `JobInfo.NETWORK_TYPE_*` or WorkManager's `NetworkType`:
 * §32.7 keeps platform detail out of the engine, and there are two schedulers
 * that have to agree. Each translates this at its own boundary, and the
 * decision itself is made once, here, where it can be tested without a device.
 */
enum class NetworkRequirement {
    /** §16's default. Cellular is never an acceptable substitute. */
    UNMETERED,

    /** The user opted in to cellular; any connection will do. */
    ANY_CONNECTED,
}

/**
 * Which transfers a scheduler should be running, and under what conditions.
 *
 * The database is authoritative (spec §2.4), so this asks only about persisted
 * state: a transfer is enqueueable because of the status it is in, never
 * because some object in this process happens to be holding it. That is what
 * makes reconciliation after process death or reboot a matter of re-reading
 * rows rather than of remembering anything.
 */
object SchedulingPolicy {

    /**
     * True when [transfer] has work a scheduler should be carrying.
     *
     * Terminal transfers are finished (§13.1). A DRAFT has no manifest yet, and
     * §24.2 has the user still in the wizard. PAUSED is the user's decision and
     * §22.1 says it stands until they resume.
     *
     * Everything else is enqueueable, including the three waiting states: a
     * transfer waiting for Wi-Fi is exactly the case the platform's own network
     * constraint exists for, and one waiting on storage or auth needs to be
     * re-examined when the app next runs rather than forgotten.
     */
    fun isEnqueueable(transfer: TransferEntity): Boolean = when (transfer.status) {
        TransferStatus.DRAFT,
        TransferStatus.PAUSED,
        -> false

        TransferStatus.PREPARING,
        TransferStatus.READY,
        TransferStatus.RUNNING,
        TransferStatus.WAITING_FOR_WIFI,
        TransferStatus.WAITING_FOR_STORAGE,
        TransferStatus.AUTH_REQUIRED,
        -> true

        TransferStatus.FAILED,
        TransferStatus.CANCELLED,
        TransferStatus.COMPLETED,
        TransferStatus.COMPLETED_WITH_ISSUES,
        -> false
    }

    /**
     * The network the platform must hold before starting [transfer] (§16).
     *
     * Stated to the platform as well as checked in the engine, on purpose.
     * §32's fifth invariant is that no transfer occurs over a disallowed
     * network, and a constraint the scheduler enforces holds even in the window
     * between the job starting and the engine's first look at connectivity.
     */
    fun networkFor(transfer: TransferEntity): NetworkRequirement = when (transfer.networkPolicy) {
        TransferNetworkPolicy.UNMETERED_ONLY -> NetworkRequirement.UNMETERED
        TransferNetworkPolicy.ANY_NETWORK -> NetworkRequirement.ANY_CONNECTED
    }

    /**
     * True when a caller may start [transfer] **now, on purpose**.
     *
     * Distinct from [isEnqueueable], and the difference is PAUSED. Resuming is
     * a user pressing a button, and §22.1's pause stands only until they do;
     * the automatic sweep must leave it alone, while an explicit start must be
     * able to lift it. Conflating the two would either make Resume do nothing
     * or make every process restart undo a pause.
     *
     * A DRAFT has no manifest yet (§24.2 has the user still in the wizard), and
     * a terminal transfer is finished (§13.1).
     */
    fun isStartable(transfer: TransferEntity): Boolean =
        !transfer.status.isTerminal && transfer.status != TransferStatus.DRAFT

    /** The transfers to (re-)enqueue after process death or reboot (§2.4). */
    fun toEnqueue(transfers: List<TransferEntity>): List<TransferEntity> = transfers.filter(::isEnqueueable)

    /**
     * Whether to hand the platform a job for a transfer it may already hold.
     *
     * Never over one it is **executing**. On API 34+ scheduling a job id the
     * platform is running stops the running job and starts a new one, and the
     * stopped run does not stop at once: it is blocked in network I/O, holding
     * a chunk, while the new one starts beside it. App launch reconciles, so
     * every launch during a transfer did this (v0.6.1's `OutOfMemoryError`).
     *
     * A job that is only waiting is replaced, which is how a transfer left
     * RUNNING with no worker gets one: a fresh job, with no backoff left over
     * from the run that died.
     */
    fun shouldSchedule(platform: PlatformJobState): Boolean = platform != PlatformJobState.EXECUTING

    /**
     * Re-enqueues every transfer with work left, through [enqueue] (§2.4).
     *
     * Shared by both schedulers so the decision is made once and can be tested
     * without a device. A transfer whose row says RUNNING is included on
     * purpose: after process death nothing is running it, whatever the row
     * says, and the scheduler decides whether a job already exists.
     */
    suspend fun reconcile(transfers: List<TransferEntity>, enqueue: suspend (TransferEntity) -> Unit) {
        toEnqueue(transfers).forEach { enqueue(it) }
    }
}

/** What the platform says about a transfer's job, as far as a scheduler can ask. */
enum class PlatformJobState {
    /** No job, or one the platform has finished with. */
    NONE,

    /** Scheduled, not running: a constraint, a quota, or the device's state is holding it. */
    WAITING,

    /** Running now. */
    EXECUTING,
}
