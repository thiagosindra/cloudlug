package dev.thiagosindra.cloudlug.database.state

import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.ACKNOWLEDGED
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.ALLOCATED
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.DELETED
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.DOWNLOADING
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.READY
import dev.thiagosindra.cloudlug.model.CacheChunkStatus.UPLOADING

/** Raised when code attempts a chunk transition spec §15.3 does not allow. */
class IllegalChunkTransitionException(
    val from: CacheChunkStatus,
    val to: CacheChunkStatus,
) : IllegalStateException("Illegal chunk transition $from -> $to")

/**
 * Chunk lifecycle (spec §15.3) and the retention rule behind engineering
 * invariant §32.4: *a cached chunk is deleted only when it is no longer needed
 * for recovery*.
 *
 * Two transitions the §15.3 chain does not draw are allowed because recovery
 * needs them: DOWNLOADING -> ALLOCATED when a download attempt is abandoned and
 * the range must be fetched again, and UPLOADING -> READY when an upload
 * attempt fails or its session expires and the same cached bytes will be sent
 * again (§22.5).
 */
object CacheChunkStateMachine {

    private val allowed: Map<CacheChunkStatus, Set<CacheChunkStatus>> = mapOf(
        ALLOCATED to setOf(DOWNLOADING, DELETED),
        DOWNLOADING to setOf(READY, ALLOCATED, DELETED),
        READY to setOf(UPLOADING, DELETED),
        UPLOADING to setOf(ACKNOWLEDGED, READY, DELETED),
        ACKNOWLEDGED to setOf(DELETED),
        DELETED to emptySet(),
    )

    fun allowedFrom(status: CacheChunkStatus): Set<CacheChunkStatus> = allowed.getValue(status)

    fun isLegal(from: CacheChunkStatus, to: CacheChunkStatus): Boolean = to in allowedFrom(from)

    fun require(from: CacheChunkStatus, to: CacheChunkStatus) {
        if (!isLegal(from, to)) throw IllegalChunkTransitionException(from, to)
    }

    /**
     * Whether [chunk] may be removed from local storage while its item is still
     * being transferred.
     *
     * A chunk is droppable once the destination has acknowledged its byte range
     * — either because the provider marked it [ACKNOWLEDGED], or because
     * [acknowledgedUploadBytes], reported by `uploadChunk`/`queryUpload`, has
     * passed its end. Anything else, including a chunk that has merely been
     * *sent*, is still needed to resume after process death (spec §14, §22.5).
     *
     * Cancellation is a separate path: §22.2 and §22.3 delete a cancelled
     * item's chunks outright, because nothing will resume them. Callers use
     * [isDiscardableOnCancellation] there rather than weakening this rule.
     */
    fun isSafeToDelete(chunk: CacheChunkEntity, acknowledgedUploadBytes: Long): Boolean = when (chunk.status) {
        ACKNOWLEDGED, DELETED -> true
        UPLOADING -> chunk.endExclusive <= acknowledgedUploadBytes
        else -> false
    }

    /** Chunks of a cancelled or failed-out item are always discardable (spec §22.2). */
    fun isDiscardableOnCancellation(chunk: CacheChunkEntity): Boolean = chunk.status != DELETED
}
