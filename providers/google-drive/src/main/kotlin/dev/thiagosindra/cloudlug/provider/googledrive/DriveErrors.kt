package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns a Drive HTTP failure into §23's provider-neutral kinds.
 *
 * Drive speaks two dialects, both captured:
 *
 * - **The API** sends `{"error": {"code", "message", "errors": [{"reason", …}]}}`,
 *   and the decision is made on `reason`, never on `message`. The message is
 *   prose for people and quotes ids ("File not found: …"); the reason is a
 *   closed vocabulary.
 * - **The token endpoint** sends plain OAuth 2: `{"error": "invalid_grant"}`,
 *   a string where the API puts an object.
 *
 * Where in the API a failure happened matters too. A `404` on a file is
 * `NOT_FOUND`; a `404` on a resumable session URI means the session has
 * expired (Google documents exactly that), and a cancelled session answers
 * `499 clientClosedRequest` thereafter — both are §22.5's
 * `UPLOAD_SESSION_EXPIRED`, so the item restarts its upload rather than fails.
 */
internal object DriveErrors {

    private val json = Json { ignoreUnknownKeys = true }

    /** Where the failing request was sent; decides what a 404 means. */
    enum class Surface { API, UPLOAD_SESSION, TOKEN }

    fun toException(
        status: Int,
        body: String,
        surface: Surface = Surface.API,
        retryAfter: Duration? = null,
    ): CloudException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error")
        val oauthError = (error as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reasons = (error as? JsonObject)?.get("errors")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { (it as? JsonObject)?.get("reason")?.let { r -> (r as? JsonPrimitive)?.content } }
            .orEmpty()
            .toSet()
        val code = reasons.firstOrNull() ?: oauthError

        return when {
            // §23: 401, or a refresh answered invalid_grant. While the OAuth app
            // is in Testing status this is what every account turns into seven
            // days after it connected, so the message says what to do.
            status == 401 || oauthError == "invalid_grant" ->
                fail(CloudErrorKind.AUTH_REQUIRED, "the Google account needs to be reconnected", code)

            // §23: Drive's throttling arrives as 403 with a reason, not as 429.
            reasons.any { it in THROTTLE_REASONS } ->
                fail(CloudErrorKind.THROTTLED, "rate limited by Google Drive", code, retryAfter ?: DEFAULT_BACKOFF)

            // §23: a storage hold for the user, not retried automatically.
            "storageQuotaExceeded" in reasons ->
                fail(CloudErrorKind.DESTINATION_STORAGE_FULL, "the Google Drive account is out of space", code)

            surface == Surface.UPLOAD_SESSION && (status == 404 || status == 410 || status == 499) ->
                fail(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "the upload session is no longer usable", code)

            status == 404 || "notFound" in reasons ->
                fail(CloudErrorKind.NOT_FOUND, "the object no longer exists in Google Drive", code)

            status == 429 -> fail(CloudErrorKind.THROTTLED, "rate limited by Google Drive", code, retryAfter ?: DEFAULT_BACKOFF)

            status == 408 || status in 500..599 ->
                fail(CloudErrorKind.TRANSIENT_NETWORK, unmapped(status, code, root != null), code, retryAfter)

            // `drive.file` cannot see what CloudLug did not create (§8.2). A
            // 403 that is not throttling or quota is that, or a permission the
            // user removed: retrying will not change the answer.
            else -> fail(CloudErrorKind.PERMANENT, unmapped(status, code, root != null), code)
        }
    }

    /**
     * Names the failure when there is no case for it (spec-proposals/v1.5 §4).
     * The reason is a closed-vocabulary tag, never user data, so it is safe in
     * a message under §26 where Drive's own `message` — which quotes ids — is
     * not.
     */
    private fun unmapped(status: Int, code: String?, parsed: Boolean): String = when {
        code != null -> "Google Drive returned $status ($code)"
        !parsed -> "Google Drive returned $status (the body was not JSON)"
        else -> "Google Drive returned $status"
    }

    private fun fail(kind: CloudErrorKind, message: String, code: String?, retryAfter: Duration? = null) =
        CloudException(kind = kind, message = message, retryAfter = retryAfter, code = code)

    private val THROTTLE_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded")

    private val DEFAULT_BACKOFF = 30.seconds
}
