package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One answer from a resumable session, read whole: its status and headers are most of it. */
internal data class SessionAnswer(val status: Int, val range: String?, val body: String, val retryAfter: Duration?)

/**
 * The HTTP shape of Drive v3, with §23 and §26 applied.
 *
 * Every method is `suspend` **and** moves to [io] before blocking, for the
 * reason `DropboxApi` records at length: `suspend` alone runs on the caller's
 * dispatcher, and the accounts screen calls on `Dispatchers.Main`.
 *
 * No method logs. §26's redaction is the interceptor on the [OkHttpClient]
 * this is given, so a call site cannot opt out of it.
 */
internal class DriveApi(
    private val tokens: DriveTokenSource,
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val files: HttpUrl = "$HOST/drive/v3/files".toHttpUrl()
    private val uploads: HttpUrl = "$HOST/upload/drive/v3/files".toHttpUrl()

    /** `about.get`, with an explicit token: authenticate() asks before the grant has an account. */
    suspend fun about(fields: String, token: String): JsonObject =
        json(Request.Builder().url("$HOST/drive/v3/about".toHttpUrl().newBuilder().addQueryParameter("fields", fields).build()), token)

    suspend fun about(account: AccountId, fields: String): JsonObject = about(fields, tokens.accessToken(account))

    suspend fun getFile(account: AccountId, id: String, fields: String = DriveObjects.FILE_FIELDS): JsonObject =
        json(Request.Builder().url(files.newBuilder().addPathSegment(id).addQueryParameter("fields", fields).build()), tokens.accessToken(account))

    suspend fun list(account: AccountId, query: String, pageToken: String?): JsonObject =
        json(
            Request.Builder().url(
                files.newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("fields", "nextPageToken,files(${DriveObjects.FILE_FIELDS})")
                    // §20.8: My Drive only. The user corpus, never shared drives.
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("pageSize", PAGE_SIZE.toString())
                    .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                    .build(),
            ),
            tokens.accessToken(account),
        )

    suspend fun createFolder(account: AccountId, name: String, parent: String): JsonObject =
        json(
            Request.Builder()
                .url(files.newBuilder().addQueryParameter("fields", DriveObjects.FILE_FIELDS).build())
                .post(metadata(name, parent, DriveObjects.FOLDER_MIME, null).toRequestBody(JSON)),
            tokens.accessToken(account),
        )

    /**
     * Starts a resumable session and returns its URI (the `Location` header).
     * [fields] is fixed into the session here: the response that finishes the
     * upload carries exactly these, which is how §21 step 1 gets its checksums.
     */
    suspend fun initiateUpload(account: AccountId, metadata: String, size: Long?, contentType: String): String = withContext(io) {
        val builder = Request.Builder()
            .url(uploads.newBuilder().addQueryParameter("uploadType", "resumable").addQueryParameter("fields", DriveObjects.FILE_FIELDS).build())
            .header("X-Upload-Content-Type", contentType)
            .post(metadata.toRequestBody(JSON))
        if (size != null) builder.header("X-Upload-Content-Length", size.toString())
        client.newCall(authorized(builder, tokens.accessToken(account))).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveErrors.toException(response.code, body, retryAfter = response.retryAfter())
            response.header("Location") ?: throw DriveErrors.toException(response.code, body)
        }
    }

    /** A PUT to a session URI: a chunk, or with no body, the §22.5 status query. */
    suspend fun putSession(account: AccountId, sessionUri: String, contentRange: String, body: RequestBody): SessionAnswer =
        session(account, Request.Builder().url(sessionUri).header("Content-Range", contentRange).put(body))

    /** §22.2: abandons a session. Drive answers 499 once it has. */
    suspend fun cancelSession(account: AccountId, sessionUri: String): SessionAnswer =
        session(account, Request.Builder().url(sessionUri).delete())

    suspend fun delete(account: AccountId, id: String): Unit = withContext(io) {
        val request = authorized(Request.Builder().url(files.newBuilder().addPathSegment(id).build()).delete(), tokens.accessToken(account))
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveErrors.toException(response.code, response.body?.string().orEmpty(), retryAfter = response.retryAfter())
        }
    }

    /** `alt=media`: the bytes, left open for the caller to stream. */
    suspend fun download(account: AccountId, id: String, range: LongRange?): Response = withContext(io) {
        val builder = Request.Builder().url(files.newBuilder().addPathSegment(id).addQueryParameter("alt", "media").build())
        if (range != null) builder.header("Range", "bytes=${range.first}-${range.last}")
        val response = client.newCall(authorized(builder, tokens.accessToken(account))).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            throw DriveErrors.toException(response.code, text, retryAfter = response.retryAfter())
        }
        response
    }

    private suspend fun session(account: AccountId, builder: Request.Builder): SessionAnswer = withContext(io) {
        client.newCall(authorized(builder, tokens.accessToken(account))).execute().use { response ->
            SessionAnswer(response.code, response.header("Range"), response.body?.string().orEmpty(), response.retryAfter())
        }
    }

    private suspend fun json(builder: Request.Builder, token: String): JsonObject = withContext(io) {
        client.newCall(authorized(builder, token)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveErrors.toException(response.code, text, retryAfter = response.retryAfter())
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        }
    }

    private fun authorized(builder: Request.Builder, token: String): Request =
        builder.header("Authorization", "Bearer $token").build()

    private fun Response.retryAfter(): Duration? =
        header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

    internal companion object {
        const val HOST = "https://www.googleapis.com"

        /** Drive's maximum; fewer round trips for a large folder in the picker. */
        const val PAGE_SIZE = 1000

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** The metadata that creates a file or folder, built by hand: two or three string fields. */
        fun metadata(name: String, parent: String, mimeType: String?, modifiedTime: String?): String = buildString {
            append("{\"name\":").append(quote(name))
            append(",\"parents\":[").append(quote(parent)).append(']')
            if (mimeType != null) append(",\"mimeType\":").append(quote(mimeType))
            if (modifiedTime != null) append(",\"modifiedTime\":").append(quote(modifiedTime))
            append('}')
        }

        fun quote(value: String): String = kotlinx.serialization.json.JsonPrimitive(value).toString()

        /** Drive's query language quotes with `'` and escapes with `\`. */
        fun literal(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}
