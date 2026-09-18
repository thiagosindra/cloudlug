package dev.thiagosindra.cloudlug.transfer.pipeline

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.transfer.policy.RetryDecision
import dev.thiagosindra.cloudlug.transfer.policy.RetryPolicy
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Raised when an expired upload session must be abandoned and restarted (spec §22.5). */
class UploadSessionRestartException(message: String) : Exception(message)

/**
 * Runs a provider call under the retry policy of spec §23.
 *
 * Every attempt that fails is recorded on the item — `retryCount`,
 * `lastErrorCode`, `lastErrorMessage` — so the decision survives process death
 * and the UI can explain why an item is slow (spec §12.2, §24.4). Holds and
 * permanent failures propagate immediately; only [RetryDecision.Retry] sleeps.
 */
class RetryExecutor(
    private val repository: TransferRepository,
    private val policy: RetryPolicy = RetryPolicy(),
    private val random: Random = Random.Default,
) {

    suspend fun <T> execute(itemId: TransferItemId, operation: String, block: suspend () -> T): T {
        var attempt = repository.findItem(itemId)?.retryCount ?: 0
        while (true) {
            try {
                return block()
            } catch (error: CloudException) {
                repository.recordRetryAttempt(itemId, error.code ?: error.kind.name, error.message)
                when (val decision = policy.decide(error, attempt, random)) {
                    is RetryDecision.Retry -> {
                        attempt++
                        delay(decision.delay)
                    }

                    is RetryDecision.Hold -> throw TransferHoldException(
                        status = decision.status,
                        message = "$operation held: ${error.message}",
                        code = error.code,
                    )

                    RetryDecision.RestartUpload -> throw UploadSessionRestartException(
                        "$operation: upload session expired",
                    )

                    RetryDecision.Fail -> throw error
                }
            }
        }
    }

    /** True when [error] ends the item rather than the transfer. */
    fun isItemFatal(error: CloudException): Boolean = !error.kind.isRetryable &&
        error.kind != CloudErrorKind.AUTH_REQUIRED &&
        error.kind != CloudErrorKind.DESTINATION_STORAGE_FULL
}
