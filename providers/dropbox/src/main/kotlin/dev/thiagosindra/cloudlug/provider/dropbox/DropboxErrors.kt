package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns a Dropbox HTTP failure into the provider-neutral kinds of §23.
 *
 * The engine never learns Dropbox specifics (§32.7), so every decision about
 * whether something is worth retrying, held for the user, or fatal for one item
 * is made here and nowhere else.
 *
 * Dropbox's error bodies are tagged unions, and the member's fields are
 * **flattened alongside the tag** rather than nested under a key named after
 * it. A real `insufficient_space` arrives as
 *
 * ```json
 * {"error":{".tag":"path","reason":{".tag":"insufficient_space"},"upload_session_id":"…"}}
 * ```
 *
 * and not as `error.path.reason`. The fixture in `src/test/resources/errors`
 * records that, because the shape was guessed wrong once and a mapper written
 * against the guess degrades a hold the user can act on into an opaque failure.
 */
internal object DropboxErrors {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [body] is the raw response body. It is parsed, never logged: §26 puts
     * that in one interceptor, and an error body can echo a path.
     */
    fun toException(status: Int, body: String, retryAfter: Duration? = null): CloudException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val summary = root?.get("error_summary")?.asText().orEmpty()
        val tags = root?.get("error").tagsOf()

        return when {
            // §23: 401, or a refresh returning invalid_grant, is AUTH_REQUIRED
            // and is never looped on.
            status == 401 || "invalid_grant" in tags || "expired_access_token" in tags ||
                "invalid_access_token" in tags ->
                fail(CloudErrorKind.AUTH_REQUIRED, "the account needs to be reconnected", summary)

            // §23: a storage hold, deliberately not retried. Dropbox reports it
            // as a write failure on the path, with the reason flattened in.
            "insufficient_space" in tags ->
                fail(CloudErrorKind.DESTINATION_STORAGE_FULL, "the destination account is out of space", summary)

            // §22.5: the session is gone, so the item restarts its upload
            // rather than failing.
            "incorrect_offset" in tags || "not_closed" in tags || "closed" in tags ||
                "lookup_failed" in tags || status == 409 && "upload_session" in summary ->
                fail(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "the upload session is no longer usable", summary)

            "not_found" in tags || "malformed_path" in tags || status == 404 ->
                fail(CloudErrorKind.NOT_FOUND, "the object no longer exists", summary)

            // Dropbox sends 429 with Retry-After; 503 can also carry one.
            status == 429 -> fail(CloudErrorKind.THROTTLED, "rate limited by Dropbox", summary, retryAfter ?: DEFAULT_BACKOFF)

            status == 408 || status in 500..599 ->
                fail(CloudErrorKind.TRANSIENT_NETWORK, "Dropbox returned $status", summary, retryAfter)

            // §20.1/§20.2 shaped refusals: a thing that has no bytes to move.
            "unsupported_file" in tags || "unsupported_extension" in tags || "unsupported_content" in tags ->
                fail(CloudErrorKind.UNSUPPORTED, "Dropbox cannot transfer this object as bytes", summary)

            else -> fail(CloudErrorKind.PERMANENT, "Dropbox returned $status", summary)
        }
    }

    /**
     * The offset Dropbox says an upload session is really at (§22.5).
     *
     * Only present on `incorrect_offset`, and it is the whole point of that
     * error: it is how a resumed upload learns where to continue from after
     * the process that was uploading died.
     */
    fun correctOffsetOf(body: String): Long? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("correct_offset")
            ?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
    }.getOrNull()

    /**
     * Every tag in an error node, whichever of the two shapes it arrived in.
     *
     * Dropbox speaks two error dialects. Its API routes send a tagged union,
     * `{"error":{".tag":"path","reason":{".tag":"insufficient_space"}}}`, with
     * the member's fields flattened alongside the tag, so a reason sits one
     * level down on some routes and two on others. Its OAuth token endpoint
     * sends plain OAuth 2, `{"error":"invalid_grant"}`, where the value is a
     * bare string. Assuming the first shape threw on the second.
     *
     * So this collects tags from anywhere in the node and treats a bare string
     * as a tag in its own right. Asking "is this tag present" rather than
     * walking a per-route path keeps one mapping for both dialects; the tags
     * are a closed vocabulary, so a collision between levels is not a risk
     * worth a brittle traversal to avoid.
     */
    private fun JsonElement?.tagsOf(): Set<String> = buildSet {
        fun walk(node: JsonElement?) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == ".tag") value.asText()?.let(::add) else walk(value)
                }
                is JsonPrimitive -> node.asText()?.let(::add)
                else -> Unit
            }
        }
        walk(this@tagsOf)
    }

    /** Null rather than an exception when the node is not a string after all. */
    private fun JsonElement.asText(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun fail(
        kind: CloudErrorKind,
        message: String,
        summary: String,
        retryAfter: Duration? = null,
    ) = CloudException(
        kind = kind,
        message = message,
        retryAfter = retryAfter,
        // `error_summary` is a tag path — "path/insufficient_space/" — not user
        // data: it names the union members that were taken, so it carries no
        // token and no filename. That makes it exactly the opaque identifier
        // §26 prefers, and the most useful thing to have when reading a report.
        code = summary.ifBlank { null },
    )

    private val DEFAULT_BACKOFF = 30.seconds
}
