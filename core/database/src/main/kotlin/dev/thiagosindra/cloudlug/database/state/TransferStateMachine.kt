package dev.thiagosindra.cloudlug.database.state

import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.model.TransferStatus.AUTH_REQUIRED
import dev.thiagosindra.cloudlug.model.TransferStatus.CANCELLED
import dev.thiagosindra.cloudlug.model.TransferStatus.COMPLETED
import dev.thiagosindra.cloudlug.model.TransferStatus.COMPLETED_WITH_ISSUES
import dev.thiagosindra.cloudlug.model.TransferStatus.DRAFT
import dev.thiagosindra.cloudlug.model.TransferStatus.FAILED
import dev.thiagosindra.cloudlug.model.TransferStatus.PAUSED
import dev.thiagosindra.cloudlug.model.TransferStatus.PREPARING
import dev.thiagosindra.cloudlug.model.TransferStatus.READY
import dev.thiagosindra.cloudlug.model.TransferStatus.RUNNING
import dev.thiagosindra.cloudlug.model.TransferStatus.WAITING_FOR_STORAGE
import dev.thiagosindra.cloudlug.model.TransferStatus.WAITING_FOR_WIFI

/** Raised when code attempts a transition spec §13 does not allow. */
class IllegalTransferTransitionException(
    val from: TransferStatus,
    val to: TransferStatus,
) : IllegalStateException("Illegal transfer transition $from -> $to")

/**
 * Legal transfer-level transitions (spec §13.1).
 *
 * Three readings of §13.1 that the diagram leaves open, resolved here and
 * recorded in docs/decisions.md ADR-0004:
 *
 *  1. The waiting states hang off RUNNING in the diagram, but enumeration needs
 *     the network and a valid token too, so PREPARING may also enter
 *     WAITING_FOR_WIFI and AUTH_REQUIRED. Failing a whole transfer because Wi-Fi
 *     dropped mid-enumeration would contradict §2.4.
 *  2. A waiting or paused transfer may be cancelled, and returns to RUNNING when
 *     its condition clears (§16, §22.1).
 *  3. "Retry incomplete files" (§22.4) re-enumerates, so FAILED,
 *     CANCELLED and COMPLETED_WITH_ISSUES may go back to PREPARING. COMPLETED
 *     may not: there is nothing incomplete to retry.
 */
object TransferStateMachine {

    private val allowed: Map<TransferStatus, Set<TransferStatus>> = mapOf(
        DRAFT to setOf(PREPARING, CANCELLED),
        PREPARING to setOf(READY, WAITING_FOR_WIFI, AUTH_REQUIRED, FAILED, CANCELLED),
        READY to setOf(RUNNING, PREPARING, CANCELLED, FAILED),
        RUNNING to setOf(
            PAUSED,
            WAITING_FOR_WIFI,
            WAITING_FOR_STORAGE,
            AUTH_REQUIRED,
            FAILED,
            CANCELLED,
            COMPLETED,
            COMPLETED_WITH_ISSUES,
        ),
        PAUSED to setOf(RUNNING, CANCELLED, FAILED),
        WAITING_FOR_WIFI to setOf(RUNNING, PREPARING, PAUSED, CANCELLED, FAILED),
        WAITING_FOR_STORAGE to setOf(RUNNING, PAUSED, CANCELLED, FAILED),
        AUTH_REQUIRED to setOf(RUNNING, PREPARING, PAUSED, CANCELLED, FAILED),
        // Terminal states. Retry re-enters PREPARING; nothing else may follow.
        FAILED to setOf(PREPARING),
        CANCELLED to setOf(PREPARING),
        COMPLETED_WITH_ISSUES to setOf(PREPARING),
        COMPLETED to emptySet(),
    )

    fun allowedFrom(status: TransferStatus): Set<TransferStatus> = allowed.getValue(status)

    fun isLegal(from: TransferStatus, to: TransferStatus): Boolean = to in allowedFrom(from)

    /** Throws [IllegalTransferTransitionException] unless [from] -> [to] is legal. */
    fun require(from: TransferStatus, to: TransferStatus) {
        if (!isLegal(from, to)) throw IllegalTransferTransitionException(from, to)
    }
}
