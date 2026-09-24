package dev.thiagosindra.cloudlug.database.state

import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferItemStatus.CACHED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.CANCELLED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.CHECKING_DESTINATION
import dev.thiagosindra.cloudlug.model.TransferItemStatus.COMPLETED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.CONFLICT
import dev.thiagosindra.cloudlug.model.TransferItemStatus.DOWNLOADING
import dev.thiagosindra.cloudlug.model.TransferItemStatus.FAILED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.PENDING
import dev.thiagosindra.cloudlug.model.TransferItemStatus.SKIPPED_DUPLICATE
import dev.thiagosindra.cloudlug.model.TransferItemStatus.SKIPPED_UNSUPPORTED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.SOURCE_CHANGED
import dev.thiagosindra.cloudlug.model.TransferItemStatus.UPLOADING
import dev.thiagosindra.cloudlug.model.TransferItemStatus.VERIFYING

/** Raised when code attempts an item transition spec §13.2 does not allow. */
class IllegalItemTransitionException(
    val from: TransferItemStatus,
    val to: TransferItemStatus,
) : IllegalStateException("Illegal item transition $from -> $to")

/**
 * Legal per-object transitions (spec §13.2).
 *
 * Notes on readings of §13.2, recorded in docs/decisions.md ADR-0005:
 *
 *  - Manifest-time classification (§20.1–§20.4) happens before any state change,
 *    so an item may be *created* directly in SKIPPED_UNSUPPORTED or CONFLICT.
 *    [isLegalInitialStatus] states which.
 *  - §14 overlaps downloading chunk N+1 with uploading chunk N, but the item
 *    chain stays PENDING -> ... -> DOWNLOADING -> CACHED -> UPLOADING as §13.2
 *    draws it. Per-chunk progress during the overlap is carried by
 *    CacheChunkStatus (§15.3) and the item's uploadedBytes, so the item state
 *    keeps meaning "furthest stage reached" and stays comparable in history.
 *  - SOURCE_CHANGED is reachable from DOWNLOADING as well as
 *    CHECKING_DESTINATION: §20.6 compares the revision before opening the
 *    stream, but a provider may only reveal the revision on the response itself.
 *  - UPLOADING -> CACHED is how an expired upload session restarts from cached
 *    chunks rather than from a partially written destination object (§22.5).
 *  - Recovery after process death re-queues active items; see
 *    [recoveryStatusFor]. Every active status can reach PENDING, because that
 *    is where recovery sends them.
 *  - Retry (§22.4) returns FAILED, CANCELLED, SOURCE_CHANGED and CONFLICT to
 *    PENDING. CONFLICT is included because §13.2 says the user may retry after
 *    removing the destination object. COMPLETED and the SKIPPED_* states are
 *    not retried: re-running them would either duplicate completed work or
 *    re-decide something the manifest already settled.
 */
object TransferItemStateMachine {

    private val allowed: Map<TransferItemStatus, Set<TransferItemStatus>> = mapOf(
        PENDING to setOf(CHECKING_DESTINATION, SKIPPED_UNSUPPORTED, CONFLICT, FAILED, CANCELLED),
        CHECKING_DESTINATION to setOf(
            DOWNLOADING,
            SKIPPED_DUPLICATE,
            SKIPPED_UNSUPPORTED,
            CONFLICT,
            SOURCE_CHANGED,
            PENDING,
            FAILED,
            CANCELLED,
        ),
        DOWNLOADING to setOf(CACHED, SOURCE_CHANGED, PENDING, FAILED, CANCELLED),
        // PENDING on each of the three below is recovery after process death,
        // and it is the only way back: the engine restarts an item from
        // CHECKING_DESTINATION, which none of these can reach directly. See
        // [recoveryStatusFor].
        CACHED to setOf(UPLOADING, PENDING, FAILED, CANCELLED),
        UPLOADING to setOf(VERIFYING, CACHED, PENDING, FAILED, CANCELLED),
        VERIFYING to setOf(COMPLETED, PENDING, FAILED, CANCELLED),
        // Terminal states (§32.9). Only "retry incomplete files" leaves them.
        COMPLETED to emptySet(),
        SKIPPED_DUPLICATE to emptySet(),
        SKIPPED_UNSUPPORTED to emptySet(),
        CONFLICT to setOf(PENDING),
        SOURCE_CHANGED to setOf(PENDING),
        FAILED to setOf(PENDING),
        CANCELLED to setOf(PENDING),
    )

    /** Statuses an item may be inserted with. */
    private val legalInitialStatuses = setOf(PENDING, SKIPPED_UNSUPPORTED, CONFLICT)

    fun allowedFrom(status: TransferItemStatus): Set<TransferItemStatus> = allowed.getValue(status)

    fun isLegal(from: TransferItemStatus, to: TransferItemStatus): Boolean = to in allowedFrom(from)

    fun isLegalInitialStatus(status: TransferItemStatus): Boolean = status in legalInitialStatuses

    fun require(from: TransferItemStatus, to: TransferItemStatus) {
        if (!isLegal(from, to)) throw IllegalItemTransitionException(from, to)
    }

    /**
     * Where an item that was mid-flight when the process died should resume
     * from (spec §2.4, §31.4).
     *
     * **Every active status goes back to PENDING**, because the engine's unit
     * of resume is the item: `FileTransferWorker` restarts a file at
     * CHECKING_DESTINATION and re-reads it from byte zero, since §19.4 computes
     * both hashes in the single pass that reads the object and a half-built
     * hash cannot be resumed from anywhere.
     *
     * This used to send UPLOADING to CACHED, reading §22.5's "restarts from its
     * cached chunks" as a state the engine could re-enter. It could not: CACHED
     * cannot reach CHECKING_DESTINATION, so an item that was uploading when the
     * process died threw [IllegalItemTransitionException] on the next run and
     * took the whole transfer down with it. VERIFYING was worse — left
     * untouched, and equally unable to restart.
     *
     * Re-running an item is safe rather than wasteful: §19.2's idempotency
     * record and §19.3's destination check both run before any byte moves, so
     * an item whose bytes did land settles as SKIPPED_DUPLICATE instead of
     * being sent twice. Terminal and idle items are left alone.
     */
    fun recoveryStatusFor(status: TransferItemStatus): TransferItemStatus = when (status) {
        CHECKING_DESTINATION, DOWNLOADING, CACHED, UPLOADING, VERIFYING -> PENDING
        else -> status
    }
}
