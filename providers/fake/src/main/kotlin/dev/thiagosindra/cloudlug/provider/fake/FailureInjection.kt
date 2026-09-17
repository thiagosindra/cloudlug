package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlin.time.Duration

/**
 * Every failure mode spec §31.3 requires `FakeCloudProvider` to simulate.
 *
 * Injections are one-shot by default — [times] attempts fail, then the call
 * succeeds — so a test can assert that the retry engine recovers rather than
 * only that it gives up. Set [times] to [Int.MAX_VALUE] for a permanent
 * condition.
 */
data class FailureInjection(
    val fault: Fault,
    val onOperation: Operation,
    var times: Int = 1,
    /** For [Fault.DISCONNECT_AFTER_BYTES] and [Fault.CORRUPT_CHUNK]. */
    val afterBytes: Long = 0,
    val retryAfter: Duration? = null,
) {
    enum class Operation {
        AUTHENTICATE,
        ENUMERATE,
        RESOLVE_METADATA,
        QUOTA,
        OPEN_DOWNLOAD,
        READ,
        PREPARE_DESTINATION,
        LOOKUP_DESTINATION,
        BEGIN_UPLOAD,
        UPLOAD_CHUNK,
        QUERY_UPLOAD,
        FINISH_UPLOAD,
        ABORT_UPLOAD,
        ANY,
    }

    enum class Fault {
        /** The connection drops partway through a download (§31.3). */
        DISCONNECT_AFTER_BYTES,

        /** HTTP 429. */
        TOO_MANY_REQUESTS,

        /** HTTP 500. */
        SERVER_ERROR,

        /** Drive's 403 with a rate-limit reason: throttling, not a permanent error (§23). */
        RATE_LIMIT_403,

        /** 401, or a refresh that returned `invalid_grant` (§23). */
        TOKEN_EXPIRED,

        /** Bytes come back altered, so the hash will not match (§31.3). */
        CORRUPT_CHUNK,

        /** The resumable upload session has expired (§22.5). */
        UPLOAD_SESSION_EXPIRED,

        /** The destination is out of space (§23). */
        DESTINATION_FULL,

        /** A request that never returns in time. */
        TIMEOUT,

        /** The process died; the provider refuses all further calls (§31.4). */
        PROCESS_INTERRUPTED,

        /** A permanent 404 on the source. */
        NOT_FOUND,
    }

    internal fun toException(): CloudException = when (fault) {
        Fault.TOO_MANY_REQUESTS -> CloudException(CloudErrorKind.THROTTLED, "429 Too Many Requests", retryAfter, "429")
        Fault.RATE_LIMIT_403 -> CloudException(
            CloudErrorKind.THROTTLED,
            "403 rate limit",
            retryAfter,
            "userRateLimitExceeded",
        )

        Fault.SERVER_ERROR -> CloudException(CloudErrorKind.TRANSIENT_NETWORK, "500 Server Error", code = "500")
        Fault.TIMEOUT -> CloudException(CloudErrorKind.TRANSIENT_NETWORK, "request timed out", code = "timeout")
        Fault.DISCONNECT_AFTER_BYTES -> CloudException(
            CloudErrorKind.TRANSIENT_NETWORK,
            "connection reset",
            code = "reset",
        )

        Fault.TOKEN_EXPIRED -> CloudException(CloudErrorKind.AUTH_REQUIRED, "invalid_grant", code = "invalid_grant")
        Fault.UPLOAD_SESSION_EXPIRED -> CloudException(
            CloudErrorKind.UPLOAD_SESSION_EXPIRED,
            "upload session expired",
            code = "session_expired",
        )

        Fault.DESTINATION_FULL -> CloudException(
            CloudErrorKind.DESTINATION_STORAGE_FULL,
            "insufficient_space",
            code = "insufficient_space",
        )

        Fault.PROCESS_INTERRUPTED -> CloudException(
            CloudErrorKind.TRANSIENT_NETWORK,
            "process interrupted",
            code = "interrupted",
        )

        Fault.NOT_FOUND -> CloudException(CloudErrorKind.NOT_FOUND, "404 Not Found", code = "404")
        Fault.CORRUPT_CHUNK -> error("CORRUPT_CHUNK alters bytes rather than throwing")
    }
}
