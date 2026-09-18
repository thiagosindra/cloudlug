package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What to do about a failed attempt (spec §23). */
sealed interface RetryDecision {

    /** Try again after [delay]. */
    data class Retry(val delay: Duration) : RetryDecision

    /**
     * Stop and hold the whole transfer in [status] until the user or the
     * environment clears the condition. Never retried on a timer.
     */
    data class Hold(val status: TransferStatus) : RetryDecision

    /** Discard the upload session and resend from cached chunks (spec §22.5). */
    data object RestartUpload : RetryDecision

    /** Permanent for this item; it ends FAILED so the transfer can still finish. */
    data object Fail : RetryDecision
}

/**
 * The retry table of spec §23.
 *
 * | Condition | Handling |
 * |---|---|
 * | 408, 429, 5xx, timeouts, resets, DNS | exponential backoff with jitter, honouring `Retry-After` |
 * | Drive 403 rate-limit reasons | throttling, not a permanent error |
 * | quota exceeded / insufficient space | hold with a user message, no automatic retry |
 * | 401 or `invalid_grant` | AUTH_REQUIRED, never a loop |
 * | permanent errors | item FAILED with a readable reason |
 *
 * Provider specifics do not appear here: adapters have already mapped their
 * statuses and error bodies onto [CloudErrorKind] (spec §32.7).
 *
 * Backoff is `base * 2^attempt`, full-jitter randomised over `[delay/2, delay]`
 * so a throttled transfer does not resynchronise its own retries, and capped at
 * [maximumDelay]. A provider-supplied `Retry-After` always wins, clamped to the
 * same cap.
 */
data class RetryPolicy(
    val maximumAttempts: Int = 5,
    val baseDelay: Duration = 1.seconds,
    val maximumDelay: Duration = 5.minutes,
) {
    init {
        require(maximumAttempts >= 1) { "maximumAttempts must be at least 1" }
        require(baseDelay > Duration.ZERO) { "baseDelay must be positive" }
        require(maximumDelay >= baseDelay) { "maximumDelay must be at least baseDelay" }
    }

    /**
     * @param attempt how many attempts have already failed for this item
     *   (0 for the first failure)
     */
    fun decide(error: CloudException, attempt: Int, random: Random = Random.Default): RetryDecision {
        require(attempt >= 0) { "attempt must not be negative" }
        return when (error.kind) {
            CloudErrorKind.AUTH_REQUIRED -> RetryDecision.Hold(TransferStatus.AUTH_REQUIRED)
            CloudErrorKind.DESTINATION_STORAGE_FULL -> RetryDecision.Hold(TransferStatus.WAITING_FOR_STORAGE)
            CloudErrorKind.UPLOAD_SESSION_EXPIRED -> RetryDecision.RestartUpload
            CloudErrorKind.NOT_FOUND,
            CloudErrorKind.UNSUPPORTED,
            CloudErrorKind.PERMANENT,
            -> RetryDecision.Fail

            CloudErrorKind.TRANSIENT_NETWORK,
            CloudErrorKind.THROTTLED,
            -> if (attempt + 1 >= maximumAttempts) {
                RetryDecision.Fail
            } else {
                RetryDecision.Retry(delayFor(attempt, error.retryAfter, random))
            }
        }
    }

    /** Exposed for the UI and for tests; [decide] is the decision-making entry point. */
    fun delayFor(attempt: Int, retryAfter: Duration? = null, random: Random = Random.Default): Duration {
        if (retryAfter != null) return minOf(retryAfter, maximumDelay)
        val exponential = baseDelay * 2.0.pow(attempt)
        val capped = minOf(exponential, maximumDelay)
        val floor = capped / 2
        val jitterRange = (capped - floor).inWholeMilliseconds
        return floor + random.nextLong(jitterRange + 1).milliseconds
    }
}
