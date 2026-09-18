package dev.thiagosindra.cloudlug.transfer.pipeline

import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferStatus

/**
 * Thrown when a condition stops the whole transfer rather than one item
 * (spec §23): authentication, destination storage, or a disallowed network.
 * The engine maps [status] onto the transfer state and stops scheduling work.
 */
class TransferHoldException(
    val status: TransferStatus,
    message: String,
    val code: String? = null,
) : Exception(message)

/** Thrown when an item settles in a terminal state that is not a failure. */
class ItemSettledException(
    val reason: ItemStatusReason,
    val terminalStatus: dev.thiagosindra.cloudlug.model.TransferItemStatus,
    message: String,
) : Exception(message)
