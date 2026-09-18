package dev.thiagosindra.cloudlug.provider

import kotlin.time.Duration

/**
 * Provider-neutral classification of a failure (spec §23).
 *
 * Adapters translate their own HTTP statuses and error bodies into these kinds
 * — Drive's `403 userRateLimitExceeded` and Dropbox's `429` both arrive as
 * [THROTTLED] — so the retry engine never learns provider specifics
 * (spec §32.7).
 */
enum class CloudErrorKind {
    /** Timeout, connection reset, DNS failure, 5xx. Retry with backoff. */
    TRANSIENT_NETWORK,

    /** Rate limited. Retry with backoff, honouring `Retry-After`. */
    THROTTLED,

    /** 401, or a refresh that returned `invalid_grant`. Never retried in a loop. */
    AUTH_REQUIRED,

    /** Destination is out of space. Held for the user, not retried automatically. */
    DESTINATION_STORAGE_FULL,

    /** The resumable upload session is gone or expired (spec §22.5). */
    UPLOAD_SESSION_EXPIRED,

    /** Source object no longer exists. */
    NOT_FOUND,

    /** The object cannot be transferred as bytes at all (spec §20.1, §20.2). */
    UNSUPPORTED,

    /** Malformed request, permission denied, or any other non-retryable failure. */
    PERMANENT,
    ;

    /** Whether the retry engine may schedule another attempt on its own (spec §23). */
    val isRetryable: Boolean get() = this == TRANSIENT_NETWORK || this == THROTTLED
}

/**
 * The exception type every adapter throws. [code] carries the provider's own
 * error identifier for diagnostics; it must never contain tokens, headers or
 * file contents (spec §26).
 */
class CloudException(
    val kind: CloudErrorKind,
    message: String,
    /** Provider-supplied retry delay, e.g. from a `Retry-After` header. */
    val retryAfter: Duration? = null,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
