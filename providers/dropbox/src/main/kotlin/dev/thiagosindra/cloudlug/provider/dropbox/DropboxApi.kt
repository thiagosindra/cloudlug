package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Supplies the credentials a [DropboxCloudProvider] call needs (spec §8.3). */
interface DropboxTokenSource {

    /**
     * A currently valid access token.
     *
     * Dropbox access tokens live about four hours, so implementations refresh
     * rather than cache indefinitely. The provider calls this per request and
     * does not hold the result, which keeps the token out of any object the
     * engine might retain or log.
     */
    suspend fun accessToken(): String

    /**
     * What the user actually granted (§7).
     *
     * Dropbox has no endpoint that answers this: the granted scopes arrive once,
     * in the OAuth token response, so whoever performed the grant is the only
     * component that knows. §7 makes this decide whether an account may act as
     * source, destination or both, so it cannot be assumed to equal what was
     * requested — a user may decline a scope at the consent screen.
     */
    suspend fun grantedScopes(): Set<String>
}

/**
 * The HTTP shape of the Dropbox API, with §23 and §26 already applied.
 *
 * ### Main-safety
 *
 * Every method here is `suspend` **and** moves to [io] before blocking. Those
 * are two different promises and only the second one is real: `suspend` does
 * not move work off a thread, it runs on whatever dispatcher the caller is
 * already on. OkHttp's `execute()` blocks, so a `suspend` function that calls
 * it without switching is main-unsafe while looking exactly like a function
 * that is not.
 *
 * That cost a crash. Every caller until v0.3 was the transfer engine, whose
 * scope is `Dispatchers.IO`, so the adapter was accidentally correct for its
 * only caller. The accounts screen called the same code from
 * `viewModelScope`, which is `Dispatchers.Main`, and Android killed the
 * process with `NetworkOnMainThreadException` — after the user had already
 * granted consent.
 *
 * Dropbox splits its surface across two hosts with different conventions: RPC
 * routes take JSON in the body, content routes take JSON in a header and bytes
 * in the body. Both are here so that [DropboxCloudProvider] reads as the
 * provider contract rather than as HTTP.
 *
 * No method logs. Logging is the one interceptor §26 asks for, installed on the
 * [OkHttpClient] this is given, so a call site cannot opt out of redaction by
 * forgetting about it.
 */
internal class DropboxApi(
    private val tokens: DropboxTokenSource,
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** A JSON-in, JSON-out route on api.dropboxapi.com. */
    suspend fun rpc(route: String, arg: JsonObject = buildJsonObject { }): JsonObject = withContext(io) {
        val body = json.encodeToString(JsonObject.serializer(), arg).toRequestBody(JSON_MEDIA_TYPE)
        val request = authorized(Request.Builder().url("$API_HOST$route").post(body))
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw response.toCloudException(text)
            if (text.isBlank()) buildJsonObject { } else json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * A route with no arguments.
     *
     * Dropbox rejects these with a `Content-Type` set, so the body is empty and
     * untyped — an easy thing to get wrong once and then carry everywhere.
     */
    suspend fun rpcWithoutArgument(route: String): JsonObject = withContext(io) {
        val request = authorized(Request.Builder().url("$API_HOST$route").post(EMPTY_BODY))
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw response.toCloudException(text)
            if (text.isBlank()) buildJsonObject { } else json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * A content route: the argument travels in `Dropbox-API-Arg`, the payload
     * in the body.
     *
     * That header carries object paths, which is why §26 redacts it by class
     * rather than treating it as harmless because it is not a credential.
     */
    suspend fun content(
        route: String,
        arg: JsonObject,
        payload: RequestBody? = null,
        range: LongRange? = null,
    ): Response = withContext(io) {
        val builder = Request.Builder()
            .url("$CONTENT_HOST$route")
            .header(ARG_HEADER, json.encodeToString(JsonObject.serializer(), arg))
            .post(payload ?: EMPTY_BODY)
        if (payload != null) builder.header("Content-Type", "application/octet-stream")
        if (range != null) builder.header("Range", "bytes=${range.first}-${range.last}")

        val response = client.newCall(authorized(builder)).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            throw response.toCloudException(text)
        }
        // The body is deliberately left open: the caller streams it. Reading
        // those bytes blocks too, and that read is the caller's to place —
        // §14's pipeline does it on its own IO scope.
        response
    }

    /**
     * A content call whose failure body the caller must read.
     *
     * §22.5's offset query is the only caller. Dropbox has no "where is this
     * session" route: it reports the session's true offset *inside* the
     * `incorrect_offset` error, so mapping that straight to an exception throws
     * away the one thing being asked for.
     */
    suspend fun contentAllowingFailure(
        route: String,
        arg: JsonObject,
        payload: RequestBody? = null,
    ): Pair<Int, String> = withContext(io) {
        val builder = Request.Builder()
            .url("$CONTENT_HOST$route")
            .header(ARG_HEADER, json.encodeToString(JsonObject.serializer(), arg))
            .post(payload ?: EMPTY_BODY)
        if (payload != null) builder.header("Content-Type", "application/octet-stream")
        client.newCall(authorized(builder)).execute().use { it.code to it.body?.string().orEmpty() }
    }

    /** Maps a status and body through §23, for a caller that read them itself. */
    fun failureFor(status: Int, body: String): CloudException = DropboxErrors.toException(status, body)

    /** The JSON result of a content route, which Dropbox returns in a header. */
    fun resultOf(response: Response): JsonObject =
        response.header(RESULT_HEADER)?.let { json.parseToJsonElement(it).jsonObject }
            ?: throw CloudException(CloudErrorKind.PERMANENT, "Dropbox returned no $RESULT_HEADER")

    private suspend fun authorized(builder: Request.Builder): Request =
        builder.header("Authorization", "Bearer ${tokens.accessToken()}").build()

    /**
     * §23's mapping, with the provider's own `Retry-After` when it sent one.
     *
     * Dropbox sends that header on 429 and sometimes on 503, and honouring it
     * is the difference between backing off and being throttled harder.
     */
    private fun Response.toCloudException(body: String): CloudException =
        DropboxErrors.toException(code, body, retryAfter())

    private fun Response.retryAfter(): Duration? =
        header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

    internal companion object {
        const val API_HOST = "https://api.dropboxapi.com"
        const val CONTENT_HOST = "https://content.dropboxapi.com"
        const val ARG_HEADER = "Dropbox-API-Arg"
        const val RESULT_HEADER = "Dropbox-API-Result"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
