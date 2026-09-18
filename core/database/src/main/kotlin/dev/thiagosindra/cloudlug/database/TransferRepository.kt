package dev.thiagosindra.cloudlug.database

import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.database.state.CacheChunkStateMachine
import dev.thiagosindra.cloudlug.database.state.TransferItemStateMachine
import dev.thiagosindra.cloudlug.database.state.TransferStateMachine
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import java.time.Clock
import java.time.Instant

/**
 * The only way the rest of the app changes transfer state (spec §13).
 *
 * Every method here is a single transaction that validates the transition
 * against the state machines *and* updates the counters that depend on it, so a
 * crash can never leave `completedFiles` disagreeing with the items it counts.
 * DAOs below this layer stay dumb; the Room implementation in v0.2 replaces
 * them without touching a rule (docs/decisions.md ADR-0002).
 *
 * Counter conventions, which §12.1 names but does not define:
 *  - `totalFiles` counts every manifest item that is not a folder, including
 *    ones classified unsupported at manifest time, so progress denominators do
 *    not shift when the engine reaches them.
 *  - `totalBytes` sums the sizes the source reported; objects with no size
 *    (provider-native documents, §20.1) contribute nothing.
 *  - `duplicateFiles`, `unsupportedFiles` and `sourceChangedFiles` are separate
 *    counters, because §13.1 makes only COMPLETED and SKIPPED_DUPLICATE count
 *    as success and the summary shows each outcome on its own line. This
 *    overrules ADR-0008, which pooled all three as `skippedFiles`.
 *  - `unknownSizeFiles` counts manifest items with no size yet (§11, §20.1);
 *    while it is non-zero `totalBytes` is a lower bound.
 */
class TransferRepository(
    private val database: CloudLugDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {

    private fun now(): Instant = clock.instant()

    // ---------------------------------------------------------------- transfers

    suspend fun createTransfer(transfer: TransferEntity): TransferEntity =
        database.withTransaction {
            check(transfer.status == TransferStatus.DRAFT) {
                "A new transfer starts in DRAFT, not ${transfer.status}"
            }
            val stamped = transfer.copy(createdAt = now(), updatedAt = now())
            database.transfers.insert(stamped)
            stamped
        }

    suspend fun findTransfer(id: TransferId): TransferEntity? = database.transfers.findById(id)

    fun observeTransfer(id: TransferId) = database.transfers.observeById(id)

    fun observeTransfers() = database.transfers.observeAll()

    fun observeItems(transferId: TransferId) = database.items.observeByTransfer(transferId)

    /**
     * Moves the transfer to [to], rejecting transitions spec §13.1 does not
     * allow. [errorCode] and [errorMessage] are recorded for the UI; they must
     * never carry tokens, headers or file contents (spec §26).
     */
    suspend fun transitionTransfer(
        id: TransferId,
        to: TransferStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): TransferEntity = database.withTransaction {
        val transfer = requireTransfer(id)
        TransferStateMachine.require(transfer.status, to)
        val updated = transfer.copy(
            status = to,
            updatedAt = now(),
            lastErrorCode = errorCode ?: transfer.lastErrorCode,
            lastErrorMessage = errorMessage ?: transfer.lastErrorMessage,
        )
        database.transfers.update(updated)
        updated
    }

    suspend fun updateEnumerationCursor(id: TransferId, cursor: String?): TransferEntity =
        database.withTransaction {
            val updated = requireTransfer(id).copy(enumerationCursor = cursor, updatedAt = now())
            database.transfers.update(updated)
            updated
        }

    suspend fun setDestinationContainer(id: TransferId, containerId: String): TransferEntity =
        database.withTransaction {
            val updated = requireTransfer(id).copy(destinationContainerId = containerId, updatedAt = now())
            database.transfers.update(updated)
            updated
        }

    /**
     * Appends an enumerated page to the manifest and advances the cursor in the
     * same transaction, so a crash mid-enumeration cannot leave a cursor that
     * skips rows (spec §11).
     *
     * Items whose `sourceObjectId` is already in this manifest are ignored: an
     * adapter that cannot resume from a cursor restarts enumeration, and
     * restarting must not duplicate rows. Returns the items actually inserted.
     */
    suspend fun appendManifestItems(
        transferId: TransferId,
        items: List<TransferItemEntity>,
        cursor: String? = null,
    ): List<TransferItemEntity> = database.withTransaction {
        val transfer = requireTransfer(transferId)
        val existing = database.items.listByTransfer(transferId).mapTo(mutableSetOf()) { it.sourceObjectId }

        val fresh = buildList {
            for (item in items) {
                check(TransferItemStateMachine.isLegalInitialStatus(item.status)) {
                    "An item cannot be created in ${item.status}"
                }
                // `add` returns false for a source object already in the
                // manifest, which also deduplicates within this page.
                if (existing.add(item.sourceObjectId)) {
                    add(item.copy(transferId = transferId, createdAt = now(), updatedAt = now()))
                }
            }
        }

        if (fresh.isNotEmpty()) {
            database.items.insertAll(fresh)
            var updated = transfer.copy(
                totalFiles = transfer.totalFiles + fresh.count { it.objectKind != CloudObjectType.FOLDER },
                totalBytes = transfer.totalBytes + fresh.sumOf { it.size ?: 0L },
                // A file with no size yet is excluded from totalBytes and
                // counted here instead, so the UI knows the denominator is a
                // lower bound (spec §11).
                unknownSizeFiles = transfer.unknownSizeFiles +
                    fresh.count { it.objectKind != CloudObjectType.FOLDER && it.size == null },
                updatedAt = now(),
            )
            // Items classified at manifest time are already terminal (§20.1–§20.4).
            fresh.filter { it.status.isTerminal }.forEach { updated = updated.applyCounter(it.status, +1, it) }
            database.transfers.update(updated.copy(enumerationCursor = cursor))
        } else {
            database.transfers.update(transfer.copy(enumerationCursor = cursor, updatedAt = now()))
        }
        fresh
    }

    /**
     * Ends a transfer whose items have all settled.
     *
     * COMPLETED only when every item ended COMPLETED or SKIPPED_DUPLICATE;
     * anything unsupported, source-changed, conflicted, failed or cancelled
     * makes it COMPLETED_WITH_ISSUES (spec §13.1). A transfer that moved half of
     * what the user selected must never read "Completed" (§2.5). This overrules
     * ADR-0008, under which skipped items alone left a transfer COMPLETED.
     */
    suspend fun finishTransfer(id: TransferId): TransferEntity = database.withTransaction {
        val transfer = requireTransfer(id)
        check(transfer.settledFiles >= transfer.totalFiles) {
            "Transfer $id still has ${transfer.totalFiles - transfer.settledFiles} unsettled items"
        }
        val to = if (transfer.settledCleanly) {
            TransferStatus.COMPLETED
        } else {
            TransferStatus.COMPLETED_WITH_ISSUES
        }
        TransferStateMachine.require(transfer.status, to)
        val updated = transfer.copy(status = to, updatedAt = now())
        database.transfers.update(updated)
        updated
    }

    // -------------------------------------------------------------------- items

    suspend fun findItem(id: TransferItemId): TransferItemEntity? = database.items.findById(id)

    suspend fun listItems(transferId: TransferId): List<TransferItemEntity> =
        database.items.listByTransfer(transferId)

    suspend fun listItemsByStatus(
        transferId: TransferId,
        statuses: Set<TransferItemStatus>,
    ): List<TransferItemEntity> = database.items.listByStatus(transferId, statuses)

    /**
     * Moves one item to [to] and adjusts its transfer's counters in the same
     * transaction (spec §13.2, §32.9).
     *
     * [mutate] lets a caller record the fields that belong to the same step —
     * the destination object ID at VERIFYING, an error code at FAILED — so
     * those never land in a separate write that a crash could lose.
     */
    suspend fun transitionItem(
        id: TransferItemId,
        to: TransferItemStatus,
        reason: ItemStatusReason? = null,
        mutate: (TransferItemEntity) -> TransferItemEntity = { it },
    ): TransferItemEntity = database.withTransaction {
        val item = requireItem(id)
        TransferItemStateMachine.require(item.status, to)

        val updated = mutate(item).copy(status = to, statusReason = reason, updatedAt = now())
        database.items.update(updated)

        var transfer = requireTransfer(item.transferId)
        if (item.status.isTerminal) transfer = transfer.applyCounter(item.status, -1, item)
        if (to.isTerminal) transfer = transfer.applyCounter(to, +1, updated)
        database.transfers.update(transfer.copy(updatedAt = now()))

        updated
    }

    /** Records downloaded bytes without changing state. Called often; kept cheap. */
    suspend fun recordDownloadProgress(id: TransferItemId, downloadedBytes: Long): TransferItemEntity =
        updateItem(id) { it.copy(downloadedBytes = downloadedBytes) }

    /**
     * Records the bytes the destination has acknowledged. This is what makes a
     * cached chunk droppable (spec §14, §32.4), so it is persisted rather than
     * held in the worker.
     */
    suspend fun recordUploadProgress(id: TransferItemId, uploadedBytes: Long): TransferItemEntity =
        updateItem(id) { it.copy(uploadedBytes = uploadedBytes) }

    /** Persists resumable-session state, including expiry (spec §22.5). */
    suspend fun recordUploadSession(
        id: TransferItemId,
        sessionId: String?,
        metadata: String? = null,
        expiresAt: Instant? = null,
    ): TransferItemEntity = updateItem(id) {
        it.copy(uploadSessionId = sessionId, uploadSessionMetadata = metadata, uploadSessionExpiresAt = expiresAt)
    }

    /** Stores the hashes computed during the single streaming pass (spec §19.4). */
    suspend fun recordComputedHashes(
        id: TransferItemId,
        sha256: ProviderHash?,
        destinationNative: ProviderHash?,
    ): TransferItemEntity = updateItem(id) {
        it.copy(computedSha256 = sha256, computedDestinationNativeHash = destinationNative)
    }

    /**
     * Records a size that was unknown at manifest time, once export has made it
     * known (spec §11, §20.1).
     *
     * `totalBytes` and `unknownSizeFiles` move in the same transaction as the
     * item, so the progress denominator can never be observed half-updated.
     */
    suspend fun recordItemSize(id: TransferItemId, size: Long): TransferItemEntity =
        database.withTransaction {
            require(size >= 0) { "Size must not be negative" }
            val item = database.items.findById(id) ?: error("No transfer item $id")
            check(item.size == null) { "Item $id already has a size of ${item.size}" }
            val updated = item.copy(size = size, updatedAt = now())
            database.items.update(updated)

            val transfer = requireTransfer(item.transferId)
            database.transfers.update(
                transfer.copy(
                    totalBytes = transfer.totalBytes + size,
                    unknownSizeFiles = transfer.unknownSizeFiles - 1,
                    updatedAt = now(),
                ),
            )
            updated
        }

    suspend fun recordRetryAttempt(
        id: TransferItemId,
        errorCode: String?,
        errorMessage: String?,
    ): TransferItemEntity = updateItem(id) {
        it.copy(retryCount = it.retryCount + 1, lastErrorCode = errorCode, lastErrorMessage = errorMessage)
    }

    /**
     * The idempotency record of spec §19.2: has this exact source object, at
     * this revision and size, already been transferred by a previous run?
     * Completed items are never blindly retransferred.
     */
    suspend fun findPriorTransferOf(
        sourceAccountId: AccountId,
        sourceObjectId: String,
        sourceRevision: String?,
        size: Long?,
    ): TransferItemEntity? =
        database.items.findCompletedForSourceObject(sourceAccountId, sourceObjectId)
            .firstOrNull { it.sourceRevision == sourceRevision && it.size == size }

    /**
     * Re-queues items that were mid-flight when the process died (spec §2.4,
     * §31.4) and returns how many were moved. Cached chunks are left in place:
     * they are what makes the restart cheap.
     */
    suspend fun recoverInterruptedItems(transferId: TransferId): Int = database.withTransaction {
        var moved = 0
        database.items.listByTransfer(transferId).forEach { item ->
            val target = TransferItemStateMachine.recoveryStatusFor(item.status)
            if (target != item.status) {
                TransferItemStateMachine.require(item.status, target)
                database.items.update(item.copy(status = target, updatedAt = now()))
                moved++
            }
        }
        moved
    }

    // ------------------------------------------------------------------- chunks

    /**
     * Records a cached byte range (spec §12.3). The row exists only once the
     * bytes are on disk, so a crash can never leave the database claiming a
     * chunk that was never written.
     */
    suspend fun insertCacheChunk(chunk: CacheChunkEntity): CacheChunkEntity = database.withTransaction {
        database.chunks.insert(chunk)
        chunk
    }

    /** Moves a chunk along the §15.3 lifecycle, rejecting illegal transitions. */
    suspend fun transitionCacheChunk(id: CacheChunkId, to: CacheChunkStatus): CacheChunkEntity =
        database.withTransaction {
            val chunk = database.chunks.findById(id) ?: error("No cache chunk $id")
            CacheChunkStateMachine.require(chunk.status, to)
            val updated = chunk.copy(status = to)
            database.chunks.update(updated)
            updated
        }

    /**
     * Acknowledges a chunk and checkpoints the item's hash in one transaction,
     * which is what spec §15.3 requires: "Each chunk acknowledgment is persisted
     * in the same transaction as the item's `hashCheckpoint`".
     *
     * Splitting the two would reintroduce exactly the gap ADR-0007 lived with.
     * If the process dies between them, either the chunk is acknowledged with a
     * stale checkpoint — and hashing would resume short, silently producing a
     * wrong digest — or the checkpoint covers bytes the destination has not
     * acknowledged. Both end in a failed verification for a sound transfer.
     */
    suspend fun acknowledgeCacheChunk(
        id: CacheChunkId,
        checkpoint: HashCheckpoint?,
    ): CacheChunkEntity = database.withTransaction {
        val chunk = database.chunks.findById(id) ?: error("No cache chunk $id")
        CacheChunkStateMachine.require(chunk.status, CacheChunkStatus.ACKNOWLEDGED)
        val updated = chunk.copy(status = CacheChunkStatus.ACKNOWLEDGED)
        database.chunks.update(updated)

        val item = database.items.findById(chunk.transferItemId)
            ?: error("No transfer item ${chunk.transferItemId}")
        database.items.update(item.copy(hashCheckpoint = checkpoint, updatedAt = now()))
        updated
    }

    /**
     * Drops a chunk row once its bytes are gone. Callers delete the file first
     * and must have established that the chunk is no longer needed for recovery
     * (spec §32.4) — [CacheChunkStateMachine.isSafeToDelete] is that check.
     */
    suspend fun deleteCacheChunk(id: CacheChunkId) = database.withTransaction {
        database.chunks.delete(id)
    }

    suspend fun listCacheChunks(itemId: TransferItemId): List<CacheChunkEntity> =
        database.chunks.listByItem(itemId)

    /** Bytes the cache currently holds, for the §15 budget. */
    suspend fun totalCachedBytes(): Long = database.chunks.totalCachedBytes()

    /** Forgets every chunk of a transfer, for cleanup after cancellation (spec §22.3). */
    suspend fun deleteCacheChunksOfTransfer(transferId: TransferId) = database.withTransaction {
        database.chunks.deleteByTransfer(transferId)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun updateItem(
        id: TransferItemId,
        mutate: (TransferItemEntity) -> TransferItemEntity,
    ): TransferItemEntity = database.withTransaction {
        val updated = mutate(requireItem(id)).copy(updatedAt = now())
        database.items.update(updated)
        updated
    }

    private suspend fun requireTransfer(id: TransferId): TransferEntity =
        database.transfers.findById(id) ?: error("No transfer $id")

    private suspend fun requireItem(id: TransferItemId): TransferItemEntity =
        database.items.findById(id) ?: error("No transfer item $id")

    /**
     * Applies [delta] to the counter matching [status], and to `completedBytes`
     * when an item enters or leaves COMPLETED.
     */
    /**
     * Moves the §12.1 outcome counters for one item.
     *
     * Folders are excluded, because `totalFiles` counts only non-folder items
     * and §24.1 reports progress as "1,103 / 1,482 files". Counting a created
     * folder as a completed file made `completedFiles` exceed `totalFiles` on
     * any transfer with a directory in it, which drove the progress bar past
     * 100% and let `finishTransfer` see a transfer as settled early.
     */
    private fun TransferEntity.applyCounter(
        status: TransferItemStatus,
        delta: Int,
        item: TransferItemEntity? = null,
    ): TransferEntity = if (item?.objectKind == CloudObjectType.FOLDER) {
        this
    } else {
        when (status) {
            TransferItemStatus.COMPLETED -> copy(
                completedFiles = completedFiles + delta,
                completedBytes = completedBytes + delta * (item?.size ?: 0L),
            )

            TransferItemStatus.SKIPPED_DUPLICATE -> copy(duplicateFiles = duplicateFiles + delta)
            TransferItemStatus.SKIPPED_UNSUPPORTED -> copy(unsupportedFiles = unsupportedFiles + delta)
            TransferItemStatus.SOURCE_CHANGED -> copy(sourceChangedFiles = sourceChangedFiles + delta)
            TransferItemStatus.CONFLICT -> copy(conflictFiles = conflictFiles + delta)
            TransferItemStatus.FAILED -> copy(failedFiles = failedFiles + delta)
            TransferItemStatus.CANCELLED -> copy(cancelledFiles = cancelledFiles + delta)

            else -> this
        }
    }
}
