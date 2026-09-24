package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.transfer.manifest.ManifestSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * The Transfer Controller of spec §35, between the Compose UI and the engine.
 *
 * [TransferEngine.run] is a long suspending call that returns only when the
 * transfer settles or parks. A UI cannot call it directly: a screen needs to
 * start work that outlives its own lifetime, observe progress while it runs, and
 * pause or cancel it from a button press *during* the run. This owns the
 * coroutine and the bookkeeping that makes those three things possible, and
 * keeps the engine free of any scope of its own — see docs/decisions.md
 * ADR-0022.
 *
 * Everything observable comes from the database rather than from this object,
 * because the database is authoritative (spec §2.4): a transfer interrupted by
 * process death is resumed by calling [start] again, and the UI sees the same
 * rows either way.
 */
class TransferController(
    private val engine: TransferEngine,
    private val repository: TransferRepository,
    private val scope: CoroutineScope,
) {

    private val jobs = mutableMapOf<TransferId, Job>()
    private val lock = Mutex()

    fun observeTransfers(): Flow<List<TransferEntity>> = repository.observeTransfers()

    fun observeTransfer(id: TransferId): Flow<TransferEntity?> = repository.observeTransfer(id)

    fun observeItems(id: TransferId): Flow<List<TransferItemEntity>> = repository.observeItems(id)

    /** Enumerates and reviews, leaving the transfer READY (spec §11, §24.2 step 5). */
    suspend fun prepare(id: TransferId, selection: CloudSelection): ManifestSummary =
        engine.prepare(id, selection)

    /**
     * Starts or resumes running [id], returning immediately.
     *
     * Idempotent while a run is in flight: pressing Start twice, or a screen
     * being recreated on rotation, must not put two workers on one transfer.
     */
    suspend fun start(id: TransferId) {
        lock.withLock {
            if (jobs[id]?.isActive == true) return
            jobs[id] = scope.launch {
                try {
                    engine.run(id)
                } finally {
                    lock.withLock { jobs.remove(id) }
                }
            }
        }
    }

    /**
     * Runs [id] to completion in the **caller's** coroutine.
     *
     * This is what a platform job needs and [start] cannot give it: a job is
     * alive only while its callback has not returned, so the work has to happen
     * inside the call rather than in a scope that outlives it. [start] keeps
     * its app-scoped behaviour for the in-app path.
     *
     * Both register in the same map, so a scheduled job and a screen pressing
     * Start cannot end up driving one transfer at once — which would be two
     * writers on one manifest.
     */
    suspend fun run(id: TransferId): TransferStatus {
        val mine = coroutineContext[Job] ?: error("run() needs a cancellable coroutine")
        val alreadyRunning = lock.withLock {
            if (jobs[id]?.isActive == true) true else { jobs[id] = mine; false }
        }
        // Not an error and not a second run: the database is authoritative
        // (§2.4), so the honest answer is whatever the transfer is doing now.
        if (alreadyRunning) return repository.findTransfer(id)?.status ?: error("No transfer $id")

        return try {
            engine.run(id)
        } finally {
            // Only if it is still ours: a pause between here and there replaces
            // the entry, and removing someone else's would leave that runner
            // unguarded.
            lock.withLock { if (jobs[id] === mine) jobs.remove(id) }
        }
    }

    /**
     * Spec §22.1. Cancelling the job stops scheduling; the engine has already
     * persisted everything it needs, so the transfer resumes from the database.
     */
    suspend fun pause(id: TransferId): TransferStatus {
        lock.withLock { jobs.remove(id) }?.cancel()
        return engine.pause(id)
    }

    suspend fun resume(id: TransferId) = start(id)

    /** Spec §22.3. */
    suspend fun cancel(id: TransferId): TransferStatus {
        lock.withLock { jobs.remove(id) }?.cancel()
        return engine.cancelTransfer(id)
    }

    /** Spec §22.2: one file, leaving the rest of the transfer running. */
    suspend fun cancelItem(id: TransferId, itemId: TransferItemId) = engine.cancelItem(id, itemId)

    /** Spec §22.4: re-queue what did not finish, then run again. */
    suspend fun retryIncomplete(id: TransferId): Int = engine.retryIncomplete(id)

    /** True while this process has a worker on [id]; the database is still the truth. */
    suspend fun isRunning(id: TransferId): Boolean = lock.withLock { jobs[id]?.isActive == true }
}
