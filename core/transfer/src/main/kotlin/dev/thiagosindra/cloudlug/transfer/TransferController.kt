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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val owned = MutableStateFlow<Set<TransferId>>(emptySet())

    /**
     * The transfers a worker in this process is running right now.
     *
     * A row that says RUNNING is not this: after process death the row is
     * still RUNNING and nothing is, and §24.3 should say "interrupted" until a
     * worker picks it up. Callers must not assume a transfer absent here has
     * stopped for good, only that nothing in this process is moving it.
     */
    val running: StateFlow<Set<TransferId>> = owned.asStateFlow()

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
            val previous = jobs[id]
            if (previous?.isActive == true) return
            val launched = scope.launch {
                try {
                    // A cancelled predecessor may still be inside blocking I/O;
                    // see [run].
                    previous?.join()
                    owned.update { it + id }
                    engine.run(id)
                } finally {
                    lock.withLock {
                        if (jobs[id] === coroutineContext[Job]) {
                            jobs.remove(id)
                            owned.update { it - id }
                        }
                    }
                }
            }
            jobs[id] = launched
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
        while (true) {
            val previous = lock.withLock {
                val holder = jobs[id]?.takeUnless { it.isCompleted }
                if (holder == null) jobs[id] = mine
                holder
            } ?: break
            // Not an error and not a second run: the database is authoritative
            // (§2.4), so the honest answer is whatever the transfer is doing now.
            if (previous.isActive) return repository.findTransfer(id)?.status ?: error("No transfer $id")

            // Cancelled, but not finished. Cancellation does not interrupt a
            // thread blocked in a socket read or an OkHttp call, so a run that
            // has been told to stop keeps its chunk buffer, request body and
            // download stream until it reaches a suspension point. Starting
            // beside it put two pipelines' memory on one heap: on API 34+
            // every app launch reschedules a running job, and the platform
            // stops the old one to start the new (the 2.5 GB OOM, v0.6.1).
            previous.join()
        }

        owned.update { it + id }
        return try {
            engine.run(id)
        } finally {
            // Only if it is still ours: a runner registers only once the one
            // before it has finished, so an entry that is not ours belongs to
            // a later runner, and removing it would leave that one unguarded.
            lock.withLock {
                if (jobs[id] === mine) {
                    jobs.remove(id)
                    owned.update { it - id }
                }
            }
        }
    }

    /**
     * Spec §22.1. Cancelling the job stops scheduling; the engine has already
     * persisted everything it needs, so the transfer resumes from the database.
     */
    suspend fun pause(id: TransferId): TransferStatus {
        // Cancelled but left in the map: the runner removes itself once it has
        // actually stopped, and until then the next one waits for it (see [run]).
        lock.withLock { jobs[id] }?.cancel()
        return engine.pause(id)
    }

    suspend fun resume(id: TransferId) = start(id)

    /** Spec §22.3. */
    suspend fun cancel(id: TransferId): TransferStatus {
        lock.withLock { jobs[id] }?.cancel()
        return engine.cancelTransfer(id)
    }

    /** Spec §22.2: one file, leaving the rest of the transfer running. */
    suspend fun cancelItem(id: TransferId, itemId: TransferItemId) = engine.cancelItem(id, itemId)

    /** Spec §22.4: re-queue what did not finish, then run again. */
    suspend fun retryIncomplete(id: TransferId): Int = engine.retryIncomplete(id)

    /** True while this process has a worker on [id]; the database is still the truth. */
    suspend fun isRunning(id: TransferId): Boolean = lock.withLock { jobs[id]?.isActive == true }
}
