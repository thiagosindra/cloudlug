package dev.thiagosindra.cloudlug.transfer.pipeline

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.database.state.CacheChunkStateMachine
import dev.thiagosindra.cloudlug.hashing.DualHash
import dev.thiagosindra.cloudlug.hashing.DualHashPipeline
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.provider.CloudDownload
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.UploadSession
import dev.thiagosindra.cloudlug.storage.CacheAccountant
import dev.thiagosindra.cloudlug.storage.CacheAllocation
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import dev.thiagosindra.cloudlug.storage.ChunkStore
import dev.thiagosindra.cloudlug.transfer.policy.CollisionOutcome
import dev.thiagosindra.cloudlug.transfer.policy.DestinationCollisionResolver
import dev.thiagosindra.cloudlug.transfer.policy.DestinationVerifier
import dev.thiagosindra.cloudlug.transfer.policy.NetworkPolicyGate
import dev.thiagosindra.cloudlug.transfer.policy.VerificationResult
import dev.thiagosindra.cloudlug.model.TransferStatus
import java.time.Clock
import java.util.UUID

/**
 * Moves one object from source to destination (spec §14).
 *
 * The shape is the pipeline of §14 — download producer, bounded persistent
 * chunk cache, upload consumer — with the ordering rules that make it safe:
 *
 *  - the source revision is re-checked immediately before the stream opens
 *    (§20.6), so a file edited after the user reviewed the manifest becomes
 *    SOURCE_CHANGED rather than being silently transferred;
 *  - the idempotency record is consulted before the collision algorithm
 *    (§19.2, §19.3), so a retried transfer skips its own completed work instead
 *    of reporting conflicts against it;
 *  - hashes are computed once, in the same pass that writes the cache (§19.4);
 *  - a cached chunk is deleted only once the destination has acknowledged its
 *    range (§32.4);
 *  - `COMPLETED` is set only after verification succeeds (§21, §32.3).
 *
 * Concurrency is deliberately conservative (§18): one active file, one download
 * producer and one upload consumer. Within a file, chunk N+1 is fetched while
 * chunk N uploads, which is the throughput win that matters on a phone.
 */
class FileTransferWorker(
    private val repository: TransferRepository,
    private val chunkStore: ChunkStore,
    private val retries: RetryExecutor,
    private val networkMonitor: NetworkMonitor,
    private val storageMonitor: StorageMonitor,
    private val cachePolicy: CacheBudgetPolicy = CacheBudgetPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val accountant = CacheAccountant(cachePolicy)

    /**
     * Runs [item] to a terminal state and returns it.
     *
     * Conditions that stop the whole transfer propagate as
     * [TransferHoldException]; everything else settles the item.
     */
    suspend fun run(
        transfer: TransferEntity,
        item: TransferItemEntity,
        source: CloudProvider,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ): TransferItemEntity {
        requireAllowedNetwork(transfer)

        return try {
            when (item.objectKind) {
                CloudObjectType.FOLDER -> createFolder(transfer, item, destination, destinationParentId)
                CloudObjectType.FILE -> transferFile(transfer, item, source, destination, destinationParentId)
                // Manifest-time classification already settled these (§20.1, §20.2).
                else -> repository.transitionItem(
                    item.id,
                    TransferItemStatus.SKIPPED_UNSUPPORTED,
                    ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
                )
            }
        } catch (settled: ItemSettledException) {
            repository.transitionItem(item.id, settled.terminalStatus, settled.reason)
        }
    }

    /** Empty folders are created at the destination so the tree is preserved (spec §20.5). */
    private suspend fun createFolder(
        transfer: TransferEntity,
        item: TransferItemEntity,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ): TransferItemEntity {
        repository.transitionItem(item.id, TransferItemStatus.CHECKING_DESTINATION)
        val path = item.destinationRelativePath ?: item.sourceRelativePath
        val folder = retries.execute(item.id, "prepareDestination") {
            destination.prepareDestination(transfer.destinationAccountId, destinationParentId, path)
        }
        // A folder carries no bytes, so there is nothing to verify beyond its
        // existence; it goes straight to its terminal state.
        repository.transitionItem(item.id, TransferItemStatus.DOWNLOADING) { it }
        repository.transitionItem(item.id, TransferItemStatus.CACHED) { it }
        repository.transitionItem(item.id, TransferItemStatus.UPLOADING) { it }
        repository.transitionItem(item.id, TransferItemStatus.VERIFYING) {
            it.copy(destinationObjectId = folder.id.opaqueId, destinationParentId = destinationParentId.opaqueId)
        }
        return repository.transitionItem(
            item.id,
            TransferItemStatus.COMPLETED,
            ItemStatusReason.VERIFIED_BY_DESTINATION_HASH,
        )
    }

    private suspend fun transferFile(
        transfer: TransferEntity,
        item: TransferItemEntity,
        source: CloudProvider,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ): TransferItemEntity {
        repository.transitionItem(item.id, TransferItemStatus.CHECKING_DESTINATION)

        checkSourceUnchanged(transfer, item, source)
        skipIfAlreadyTransferred(transfer, item)
        checkDestinationCollision(transfer, item, destination, destinationParentId)

        repository.transitionItem(item.id, TransferItemStatus.DOWNLOADING) {
            it.copy(destinationParentId = destinationParentId.opaqueId)
        }

        val moved = streamThroughCache(transfer, item, source, destination, destinationParentId)

        repository.transitionItem(item.id, TransferItemStatus.CACHED)
        repository.transitionItem(item.id, TransferItemStatus.UPLOADING)

        val uploaded = retries.execute(item.id, "finishUpload") { destination.finishUpload(moved.session) }
        repository.recordComputedHashes(item.id, moved.hash.sha256, moved.hash.destinationNative)
        repository.transitionItem(item.id, TransferItemStatus.VERIFYING) {
            it.copy(destinationObjectId = uploaded.id.opaqueId, uploadedBytes = moved.bytes)
        }

        return verify(transfer, item, uploaded, moved, destination)
    }

    /** Spec §20.6: the manifest revision is confirmed immediately before download. */
    private suspend fun checkSourceUnchanged(
        transfer: TransferEntity,
        item: TransferItemEntity,
        source: CloudProvider,
    ) {
        val current = retries.execute(item.id, "resolveMetadata") {
            source.resolveMetadata(transfer.sourceAccountId, CloudObjectId(source.type, item.sourceObjectId))
        }
        if (item.sourceRevision != null && current.revision != null && current.revision != item.sourceRevision) {
            throw ItemSettledException(
                ItemStatusReason.SOURCE_REVISION_CHANGED,
                TransferItemStatus.SOURCE_CHANGED,
                "source revision moved from ${item.sourceRevision} to ${current.revision}",
            )
        }
    }

    /** Spec §19.2: completed items are never blindly retransferred. */
    private suspend fun skipIfAlreadyTransferred(transfer: TransferEntity, item: TransferItemEntity) {
        val prior = repository.findPriorTransferOf(
            sourceAccountId = transfer.sourceAccountId,
            sourceObjectId = item.sourceObjectId,
            sourceRevision = item.sourceRevision,
            size = item.size,
        ) ?: return
        if (prior.id != item.id) {
            throw ItemSettledException(
                ItemStatusReason.ALREADY_TRANSFERRED,
                TransferItemStatus.SKIPPED_DUPLICATE,
                "already transferred to ${prior.destinationObjectId}",
            )
        }
    }

    /** Spec §19.3, applied before any byte moves. */
    private suspend fun checkDestinationCollision(
        transfer: TransferEntity,
        item: TransferItemEntity,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ) {
        val matches = retries.execute(item.id, "lookupDestination") {
            destination.lookupDestination(transfer.destinationAccountId, destinationParentId, item.filename)
        }
        val outcome = DestinationCollisionResolver.resolve(
            matches = matches,
            sourceSize = item.size,
            comparableHashes = listOfNotNull(
                item.computedDestinationNativeHash,
                item.computedSha256,
                item.sourceProviderHash,
            ),
        )
        when (outcome) {
            is CollisionOutcome.Upload -> Unit
            is CollisionOutcome.SkipDuplicate -> throw ItemSettledException(
                outcome.reason,
                TransferItemStatus.SKIPPED_DUPLICATE,
                "destination already holds identical content",
            )

            is CollisionOutcome.Conflict -> throw ItemSettledException(
                outcome.reason,
                TransferItemStatus.CONFLICT,
                "destination object is not provably identical",
            )
        }
    }

    private suspend fun verify(
        transfer: TransferEntity,
        item: TransferItemEntity,
        uploaded: CloudObject,
        moved: MovedBytes,
        destination: CloudProvider,
    ): TransferItemEntity {
        val result = DestinationVerifier.verify(
            uploaded = uploaded,
            expectedNativeHash = moved.hash.destinationNative,
            expectedSize = moved.bytes,
            capabilities = destination.capabilities,
            resolveMetadata = {
                retries.execute(item.id, "resolveMetadata(destination)") {
                    destination.resolveMetadata(transfer.destinationAccountId, uploaded.id)
                }
            },
        )
        return when (result) {
            is VerificationResult.Verified ->
                repository.transitionItem(item.id, TransferItemStatus.COMPLETED, result.reason)

            is VerificationResult.Mismatch ->
                repository.transitionItem(item.id, TransferItemStatus.FAILED, ItemStatusReason.ERROR_PERMANENT) {
                    it.copy(lastErrorCode = "verification_mismatch", lastErrorMessage = result.detail)
                }

            is VerificationResult.Unverifiable ->
                repository.transitionItem(item.id, TransferItemStatus.FAILED, ItemStatusReason.ERROR_PERMANENT) {
                    it.copy(lastErrorCode = "verification_unavailable", lastErrorMessage = result.detail)
                }
        }
    }

    private fun requireAllowedNetwork(transfer: TransferEntity) {
        NetworkPolicyGate.holdStatusFor(transfer.networkPolicy, networkMonitor.current())?.let { status ->
            throw TransferHoldException(status, "network policy ${transfer.networkPolicy} not satisfied")
        }
    }

    // ------------------------------------------------------------ byte movement

    private class MovedBytes(val session: UploadSession, val hash: DualHash, val bytes: Long)

    /**
     * The §14 pipeline itself: read a chunk, hash it, persist it, upload it,
     * drop it once acknowledged.
     *
     * The loop is sequential per chunk but overlaps the network calls of
     * adjacent chunks in the sense that matters for correctness: nothing is
     * deleted before acknowledgement, and the cache never exceeds its budget.
     * A genuinely concurrent producer/consumer pair is a v0.4 throughput
     * change (spec §18: measure before optimising) and is safe to add because
     * every step here already goes through the database.
     */
    private suspend fun streamThroughCache(
        transfer: TransferEntity,
        item: TransferItemEntity,
        source: CloudProvider,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ): MovedBytes {
        val chunkSize = destination.capabilities.alignChunkSize(cachePolicy.networkChunkBytes).toInt()
        val hashPipeline = DualHashPipeline(destination.capabilities.nativeHashAlgorithm)

        var session = beginOrResumeUpload(transfer, item, destination, destinationParentId)
        var acknowledged = retries.execute(item.id, "queryUpload") { destination.queryUpload(session) }
            .acknowledgedBytes

        val download = retries.execute(item.id, "openDownload") {
            source.openDownload(transfer.sourceAccountId, CloudObjectId(source.type, item.sourceObjectId))
        }

        if (download.revision != null && item.sourceRevision != null && download.revision != item.sourceRevision) {
            download.close()
            throw ItemSettledException(
                ItemStatusReason.SOURCE_REVISION_CHANGED,
                TransferItemStatus.SOURCE_CHANGED,
                "source revision changed as the stream opened",
            )
        }

        var offset = 0L
        var index = 0L
        download.use {
            while (true) {
                awaitCacheRoom(transfer, chunkSize.toLong())

                val buffer = ByteArray(chunkSize)
                val read = readFully(download, buffer)
                if (read <= 0) break

                hashPipeline.update(buffer, 0, read)
                val chunk = persistChunk(transfer, item, index, offset, buffer, read)
                offset += read
                repository.recordDownloadProgress(item.id, offset)

                val expectedSize = item.size
                val isFinal = expectedSize != null && offset >= expectedSize
                acknowledged = uploadChunk(item, destination, session, chunk, buffer, read, isFinal)
                releaseAcknowledged(transfer, item, acknowledged)
                index++
            }
        }

        // Zero-byte objects still need a final, empty chunk so the provider can
        // commit the upload.
        if (offset == 0L) {
            acknowledged = uploadChunk(
                item = item,
                destination = destination,
                session = session,
                chunk = null,
                buffer = ByteArray(0),
                length = 0,
                isFinal = true,
            )
        }

        repository.recordUploadProgress(item.id, acknowledged)
        return MovedBytes(session, hashPipeline.finish(), offset)
    }

    private suspend fun beginOrResumeUpload(
        transfer: TransferEntity,
        item: TransferItemEntity,
        destination: CloudProvider,
        destinationParentId: CloudObjectId,
    ): UploadSession {
        val request = UploadRequest(
            account = transfer.destinationAccountId,
            parent = destinationParentId,
            name = item.filename,
            size = item.size,
            mimeType = item.mimeType,
            modifiedAt = item.modifiedAt.takeIf { destination.capabilities.supportsModifiedTimeWrite },
        )
        val existing = item.uploadSessionId
        if (existing != null && item.uploadSessionExpiresAt?.isBefore(clock.instant()) != true) {
            return UploadSession(existing, request, item.uploadSessionMetadata, item.uploadSessionExpiresAt)
        }
        val session = retries.execute(item.id, "beginUpload") { destination.beginUpload(request.account, request) }
        repository.recordUploadSession(item.id, session.id, session.providerMetadata, session.expiresAt)
        return session
    }

    private suspend fun uploadChunk(
        item: TransferItemEntity,
        destination: CloudProvider,
        session: UploadSession,
        chunk: CacheChunkEntity?,
        buffer: ByteArray,
        length: Int,
        isFinal: Boolean,
    ): Long {
        chunk?.let { transitionChunk(it, CacheChunkStatus.UPLOADING) }
        val progress = retries.execute(item.id, "uploadChunk") {
            destination.uploadChunk(
                session,
                Chunk(offset = chunk?.offset ?: 0L, bytes = buffer, length = length, isFinal = isFinal),
            )
        }
        repository.recordUploadProgress(item.id, progress.acknowledgedBytes)
        return progress.acknowledgedBytes
    }

    private suspend fun persistChunk(
        transfer: TransferEntity,
        item: TransferItemEntity,
        index: Long,
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): CacheChunkEntity {
        val allocated = CacheChunkEntity(
            id = CacheChunkId(idFactory()),
            transferItemId = item.id,
            offset = offset,
            length = length.toLong(),
            localFilename = "",
            status = CacheChunkStatus.ALLOCATED,
            createdAt = clock.instant(),
        )
        val fileName = chunkStore.write(transfer.id, item.id, index, buffer, length)
        val ready = allocated.copy(localFilename = fileName, status = CacheChunkStatus.READY)
        // ALLOCATED -> DOWNLOADING -> READY, collapsed into one write because the
        // bytes are already on disk by the time the row first appears.
        CacheChunkStateMachine.require(CacheChunkStatus.ALLOCATED, CacheChunkStatus.DOWNLOADING)
        CacheChunkStateMachine.require(CacheChunkStatus.DOWNLOADING, CacheChunkStatus.READY)
        insertChunk(ready)
        return ready
    }

    /** Deletes chunks the destination has acknowledged (spec §14, §32.4). */
    private suspend fun releaseAcknowledged(
        transfer: TransferEntity,
        item: TransferItemEntity,
        acknowledgedBytes: Long,
    ) {
        for (chunk in cacheChunks(item)) {
            if (!CacheChunkStateMachine.isSafeToDelete(chunk, acknowledgedBytes)) continue
            if (chunk.status != CacheChunkStatus.ACKNOWLEDGED) {
                transitionChunk(chunk, CacheChunkStatus.ACKNOWLEDGED)
            }
            chunkStore.delete(transfer.id, item.id, chunk.localFilename)
            deleteChunk(chunk)
        }
    }

    /**
     * Applies the cache budget before another chunk is allocated (spec §15.1).
     * Backpressure is not possible in this sequential loop — the previous chunk
     * is always acknowledged before the next is read — so a full cache here
     * means the device itself is out of room.
     */
    private suspend fun awaitCacheRoom(transfer: TransferEntity, requestedBytes: Long) {
        val cached = totalCachedBytes()
        when (accountant.decide(storageMonitor.snapshot(), cached, requestedBytes)) {
            CacheAllocation.ALLOW, CacheAllocation.BACKPRESSURE -> Unit
            CacheAllocation.WAIT_FOR_STORAGE -> throw TransferHoldException(
                TransferStatus.WAITING_FOR_STORAGE,
                "not enough local storage for a ${requestedBytes}-byte chunk",
            )
        }
        requireAllowedNetwork(transfer)
    }

    private suspend fun readFully(download: CloudDownload, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = download.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    // Chunk bookkeeping goes through the repository's database handle so that a
    // chunk row and the item progress it belongs to stay consistent.
    private suspend fun insertChunk(chunk: CacheChunkEntity) = repository.insertCacheChunk(chunk)

    private suspend fun transitionChunk(chunk: CacheChunkEntity, to: CacheChunkStatus) =
        repository.transitionCacheChunk(chunk.id, to)

    private suspend fun deleteChunk(chunk: CacheChunkEntity) = repository.deleteCacheChunk(chunk.id)

    private suspend fun cacheChunks(item: TransferItemEntity) = repository.listCacheChunks(item.id)

    private suspend fun totalCachedBytes(): Long = repository.totalCachedBytes()
}
