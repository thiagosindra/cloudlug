package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.database.state.CacheChunkStateMachine
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.UploadSession
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import dev.thiagosindra.cloudlug.storage.ChunkStore
import dev.thiagosindra.cloudlug.transfer.manifest.EnclosingFolderNamer
import dev.thiagosindra.cloudlug.transfer.manifest.ManifestBuilder
import dev.thiagosindra.cloudlug.transfer.manifest.ManifestSummary
import dev.thiagosindra.cloudlug.transfer.pipeline.FileTransferWorker
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.RetryExecutor
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.TransferHoldException
import dev.thiagosindra.cloudlug.transfer.policy.RetryPolicy
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.ZoneId

/** Raised when the destination provably cannot hold the transfer (spec §20.7). */
class InsufficientDestinationQuotaException(
    val requiredBytes: Long,
    message: String,
) : Exception(message)

/**
 * Drives a transfer through the states of spec §13.1.
 *
 * The engine is a disposable executor over an authoritative database
 * (spec §2.4): everything it learns is written down before it acts on it, so
 * killing the process at any point loses at most the work in flight. It knows
 * nothing about any particular cloud service — providers arrive through
 * [ProviderRegistry], and every behavioural difference comes from
 * `ProviderCapabilities` (spec §32.7).
 */
class TransferEngine(
    private val repository: TransferRepository,
    private val providers: ProviderRegistry,
    private val chunkStore: ChunkStore,
    private val networkMonitor: NetworkMonitor,
    private val storageMonitor: StorageMonitor,
    private val cachePolicy: CacheBudgetPolicy = CacheBudgetPolicy(),
    retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val retries = RetryExecutor(repository, retryPolicy)

    private val worker = FileTransferWorker(
        repository = repository,
        chunkStore = chunkStore,
        retries = retries,
        networkMonitor = networkMonitor,
        storageMonitor = storageMonitor,
        cachePolicy = cachePolicy,
        clock = clock,
    )

    /**
     * Enumerates the selection and checks destination quota, leaving the
     * transfer READY (spec §11, §20.7).
     *
     * It deliberately does **not** create the enclosing folder. §10 moves that
     * to `READY -> RUNNING`, because creating it here leaves an empty folder
     * behind at the destination whenever a user reviews a manifest and
     * abandons it (spec §24.2 step 5).
     *
     * Nothing here moves file bytes, so it is safe to run again after a crash:
     * the manifest deduplicates by source object.
     */
    suspend fun prepare(
        transferId: TransferId,
        selection: CloudSelection,
        resumeAfter: CloudObjectId? = null,
    ): ManifestSummary {
        var transfer = repository.transitionTransfer(transferId, TransferStatus.PREPARING)
        val source = providers.provider(transfer.sourceProvider)
        val destination = providers.provider(transfer.destinationProvider)

        val summary = ManifestBuilder(repository, clock).build(
            transfer = transfer,
            source = source,
            destinationCapabilities = destination.capabilities,
            selection = selection,
            resumeAfter = resumeAfter,
        )

        transfer = repository.findTransfer(transferId) ?: error("No transfer $transferId")
        checkDestinationQuota(transfer, destination)

        repository.transitionTransfer(transferId, TransferStatus.READY)
        return summary
    }

    /** Spec §20.7: refuse before starting rather than failing mid-transfer. */
    private suspend fun checkDestinationQuota(transfer: TransferEntity, destination: CloudProvider) {
        val quota = destination.quota(transfer.destinationAccountId) ?: return
        if (quota.cannotFit(transfer.totalBytes)) {
            repository.transitionTransfer(
                transfer.id,
                TransferStatus.FAILED,
                errorCode = "destination_quota_insufficient",
                errorMessage = "destination cannot hold ${transfer.totalBytes} bytes",
            )
            throw InsufficientDestinationQuotaException(
                transfer.totalBytes,
                "destination account has insufficient free space",
            )
        }
    }

    /** Spec §10: one enclosing folder per transfer, never merged with another's. */
    private suspend fun createEnclosingFolder(transfer: TransferEntity, destination: CloudProvider) {
        if (transfer.destinationContainerId != null) return

        val root = CloudObjectId(destination.type, transfer.destinationRootId)
        val preferred = transfer.destinationContainerName
            .ifBlank { EnclosingFolderNamer.nameFor(clock.instant(), zone) }

        var name = preferred
        var attempt = 2
        while (destination.lookupDestination(transfer.destinationAccountId, root, name).isNotEmpty()) {
            name = "$preferred ($attempt)"
            attempt++
        }

        val container = destination.prepareDestination(
            transfer.destinationAccountId,
            root,
            CloudPath.of(name),
        )
        repository.setDestinationContainer(transfer.id, container.id.opaqueId)
    }

    /**
     * Takes a READY transfer to RUNNING, creating the enclosing folder on the
     * way (spec §10).
     *
     * A separate step from [run] because §10 attaches folder creation to this
     * one transition, and the two have different failure meanings: if creation
     * fails the transfer fails fast with zero bytes moved, whereas a failure
     * inside [run] leaves a partially transferred manifest to resume. [run]
     * calls this itself when a transfer is not yet RUNNING, so resuming a paused
     * transfer does not create a second folder.
     */
    suspend fun start(transferId: TransferId): TransferEntity {
        val transfer = repository.findTransfer(transferId) ?: error("No transfer $transferId")
        if (transfer.status == TransferStatus.RUNNING) return transfer
        createEnclosingFolder(transfer, providers.provider(transfer.destinationProvider))
        return repository.transitionTransfer(transferId, TransferStatus.RUNNING)
    }

    /**
     * Runs every unsettled item and returns the transfer's resulting status.
     *
     * Items are processed one at a time (spec §18), folders before the files
     * that live in them. A [TransferHoldException] stops scheduling and parks
     * the transfer in the state the condition calls for — waiting for Wi-Fi,
     * storage or authentication — leaving everything resumable.
     */
    suspend fun run(transferId: TransferId): TransferStatus {
        var transfer = repository.findTransfer(transferId) ?: error("No transfer $transferId")
        if (transfer.status != TransferStatus.RUNNING) transfer = start(transferId)

        val source = providers.provider(transfer.sourceProvider)
        val destination = providers.provider(transfer.destinationProvider)
        repository.recoverInterruptedItems(transferId)

        val container = CloudObjectId(
            destination.type,
            transfer.destinationContainerId ?: error("Transfer $transferId has no enclosing folder"),
        )
        val parents = mutableMapOf<CloudPath, CloudObjectId>(CloudPath.ROOT to container)

        for (item in orderedWork(transferId)) {
            val current = repository.findItem(item.id) ?: continue
            if (current.status.isTerminal) continue
            if (repository.findTransfer(transferId)?.status != TransferStatus.RUNNING) break

            try {
                val parent = resolveParent(transfer, destination, parents, current)
                worker.run(transfer, current, source, destination, parent)
            } catch (cancellation: CancellationException) {
                // Pause cancels the job (§22.1). That must keep unwinding, and
                // must be re-thrown before the suspending lookup below, which
                // would itself throw once the coroutine is cancelled.
                throw cancellation
            } catch (hold: TransferHoldException) {
                repository.transitionTransfer(
                    transferId,
                    hold.status,
                    errorCode = hold.code,
                    errorMessage = hold.message,
                )
                return hold.status
            } catch (error: Exception) {
                // §22.2: `cancelItem` settles the item and aborts its upload
                // session while this worker is still mid-file, so the worker's
                // next call fails — an aborted session reports "expired". That
                // throw must not end the transfer. The user asked for one file
                // to stop, not all of them, and the rest are untouched.
                //
                // Before this check, cancelling the file *in flight* threw
                // UploadSessionRestartException past both handlers, killed the
                // run and left every remaining item PENDING with the transfer
                // stuck in RUNNING. Only reachable with a slow source, which is
                // why §31.3 requires one.
                val settledMeanwhile = repository.findItem(current.id)?.status?.isTerminal == true
                if (settledMeanwhile) continue

                // The retry policy already decided this is permanent for the item;
                // the transfer carries on so it can finish COMPLETED_WITH_ISSUES.
                if (error !is CloudException) throw error
                repository.transitionItem(current.id, TransferItemStatus.FAILED, ItemStatusReason.ERROR_PERMANENT) {
                    it.copy(lastErrorCode = error.code ?: error.kind.name, lastErrorMessage = error.message)
                }
            }
        }

        val settled = repository.findTransfer(transferId) ?: error("No transfer $transferId")
        return if (settled.status == TransferStatus.RUNNING && settled.settledFiles >= settled.totalFiles) {
            repository.finishTransfer(transferId).status
        } else {
            settled.status
        }
    }

    /** Folders first, then files, each group in manifest order (spec §20.5). */
    private suspend fun orderedWork(transferId: TransferId): List<TransferItemEntity> {
        val items = repository.listItems(transferId)
        val folders = items.filter { it.objectKind == CloudObjectType.FOLDER }
            .sortedBy { it.sourceRelativePath.segments.size }
        val files = items - folders.toSet()
        return folders + files
    }

    private suspend fun resolveParent(
        transfer: TransferEntity,
        destination: CloudProvider,
        parents: MutableMap<CloudPath, CloudObjectId>,
        item: TransferItemEntity,
    ): CloudObjectId {
        val path = (item.destinationRelativePath ?: item.sourceRelativePath).parent ?: CloudPath.ROOT
        parents[path]?.let { return it }

        val container = parents.getValue(CloudPath.ROOT)
        val created = retries.execute(item.id, "prepareDestination") {
            destination.prepareDestination(transfer.destinationAccountId, container, path)
        }
        parents[path] = created.id
        return created.id
    }

    /** Spec §22.1. Cached chunks are retained: they are what makes resuming cheap. */
    suspend fun pause(transferId: TransferId): TransferStatus =
        repository.transitionTransfer(transferId, TransferStatus.PAUSED).status

    /** Spec §16, §22.1: resume once the holding condition has cleared. */
    suspend fun resume(transferId: TransferId): TransferStatus = run(transferId)

    /**
     * Spec §22.2: cancel one file, abort its destination session where
     * appropriate, delete its chunks, and carry on with the rest.
     */
    suspend fun cancelItem(transferId: TransferId, itemId: TransferItemId) {
        val item = repository.findItem(itemId) ?: return
        if (item.status.isTerminal) return

        val transfer = repository.findTransfer(transferId) ?: return
        val destination = providers.provider(transfer.destinationProvider)

        // Record the cancellation *first* (ADR-0027). Aborting the session kills
        // it under a worker that is still mid-file, and that worker's next call
        // fails; `run()` decides whether such a failure is expected by re-reading
        // this row. Abort first and there is a window where the row still says
        // DOWNLOADING, so the failure looks like a genuine fault and ends the
        // whole transfer — the §22.2 bug of PR #8, reached by a different
        // interleaving. Marking first closes it: whatever the worker hits
        // afterwards, the item is already terminal.
        repository.transitionItem(itemId, TransferItemStatus.CANCELLED, ItemStatusReason.CANCELLED_BY_USER)
        abortUploadSession(transfer, item, destination)
        discardChunks(transferId, itemId)
    }

    /**
     * Spec §22.3: stop everything, clean up unfinished work, keep the history.
     * Files already completed at the destination are left untouched
     * (invariant §32.1, §32.2).
     */
    suspend fun cancelTransfer(transferId: TransferId): TransferStatus {
        val transfer = repository.findTransfer(transferId) ?: error("No transfer $transferId")
        val destination = providers.provider(transfer.destinationProvider)

        repository.listItems(transferId).filterNot { it.status.isTerminal }.forEach { item ->
            abortUploadSession(transfer, item, destination)
            discardChunks(transferId, item.id)
            repository.transitionItem(item.id, TransferItemStatus.CANCELLED, ItemStatusReason.CANCELLED_BY_USER)
        }
        chunkStore.deleteTransfer(transferId)
        repository.deleteCacheChunksOfTransfer(transferId)
        return repository.transitionTransfer(transferId, TransferStatus.CANCELLED).status
    }

    /**
     * Spec §22.4: "Retry incomplete files" — re-queue what did not finish,
     * without duplicating completed work. Completed and skipped items keep
     * their outcome; the transfer goes back to PREPARING so enumeration can
     * pick up new revisions.
     */
    suspend fun retryIncomplete(transferId: TransferId): Int {
        val retryable = setOf(
            TransferItemStatus.FAILED,
            TransferItemStatus.CANCELLED,
            TransferItemStatus.SOURCE_CHANGED,
            TransferItemStatus.CONFLICT,
        )
        val items = repository.listItemsByStatus(transferId, retryable)
        items.forEach { repository.transitionItem(it.id, TransferItemStatus.PENDING) }
        repository.transitionTransfer(transferId, TransferStatus.PREPARING)
        repository.transitionTransfer(transferId, TransferStatus.READY)
        return items.size
    }

    private suspend fun abortUploadSession(
        transfer: TransferEntity,
        item: TransferItemEntity,
        destination: CloudProvider,
    ) {
        val sessionId = item.uploadSessionId ?: return
        try {
            destination.abortUpload(
                UploadSession(
                    id = sessionId,
                    request = UploadRequest(
                        account = transfer.destinationAccountId,
                        parent = CloudObjectId(
                            destination.type,
                            item.destinationParentId ?: transfer.destinationContainerId.orEmpty(),
                        ),
                        name = item.filename,
                        size = item.size,
                        mimeType = item.mimeType,
                    ),
                    providerMetadata = item.uploadSessionMetadata,
                    expiresAt = item.uploadSessionExpiresAt,
                ),
            )
        } catch (error: CloudException) {
            // Best effort: an unreachable provider must not block cancellation.
            repository.recordRetryAttempt(item.id, error.code ?: error.kind.name, error.message)
        }
        repository.recordUploadSession(item.id, sessionId = null)
    }

    private suspend fun discardChunks(transferId: TransferId, itemId: TransferItemId) {
        repository.listCacheChunks(itemId)
            .filter { CacheChunkStateMachine.isDiscardableOnCancellation(it) }
            .forEach { chunk ->
                chunkStore.delete(transferId, itemId, chunk.localFilename)
                repository.deleteCacheChunk(chunk.id)
            }
        chunkStore.deleteItem(transferId, itemId)
    }
}
