package dev.thiagosindra.cloudlug.tools.drive

import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.googledrive.GoogleOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.system.exitProcess

/**
 * The tooling side of `docs/oauth.md`'s two clients.
 *
 * The Desktop client exists only so a terminal can run a loopback redirect,
 * which Google no longer allows an Android client to do. It is never compiled
 * into the app: `:app` does not depend on anything under `tools/`.
 */
object DriveTooling {

    /** Public, like every client id. */
    const val DESKTOP_CLIENT_ID = "17997718186-k07nk3kp29pedomv7fk8o5u14799rt5v.apps.googleusercontent.com"

    /**
     * The folder every tool and live test writes beneath (§31.2).
     *
     * Found by name among files this project's clients created, because under
     * `drive.file` that is all a token can see: a folder made in the Drive web
     * UI with this name is invisible to it.
     */
    const val TEST_ROOT_NAME = "cloudlug-contract-tests"

    const val FOLDER_MIME = "application/vnd.google-apps.folder"

    /**
     * Google requires a Desktop client's secret at the token endpoint even
     * under PKCE. It lives only in the environment of the process that needs
     * it — never a file, never a Gradle property, never a commit (CLAUDE.md).
     */
    fun clientSecret(): String = env("DRIVE_TOOL_CLIENT_SECRET") ?: fail(
        "DRIVE_TOOL_CLIENT_SECRET is not set. It is the Desktop tooling client's secret; " +
            "export it for this command only.",
    )

    fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Stops the tool with a sentence for the person running it. Thrown rather
     * than exiting here so the guards below can be tested; [runTool] turns it
     * into an exit code.
     */
    fun fail(message: String): Nothing = throw ToolFailure(message)
}

class ToolFailure(message: String) : Exception(message)

/** Runs a tool's body, printing a [ToolFailure] as one line and exiting 1. */
fun runTool(name: String, body: () -> Unit) {
    try {
        body()
    } catch (failure: ToolFailure) {
        System.err.println("$name: ${failure.message}")
        exitProcess(1)
    }
}

/** One HTTP exchange, kept whole so a capture can record it. */
data class Exchange(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val ok: Boolean get() = status in 200..299
}

/**
 * The Drive v3 calls the tools make, over plain OkHttp.
 *
 * Deliberately thin and deliberately not the adapter: its job is to put real
 * requests on the wire so the adapter can later be written against what came
 * back. It returns every response as an [Exchange] and interprets none of them
 * beyond what the calling tool asks.
 *
 * Nothing here logs a header, and [Exchange] never carries a request header,
 * so the bearer token cannot reach a fixture or a terminal (§26).
 */
class DriveHttp(
    private val client: OkHttpClient,
    private val token: String,
    private val apiBase: HttpUrl = "https://www.googleapis.com".toHttpUrl(),
) {
    private val files: HttpUrl get() = apiBase.newBuilder().addPathSegments("drive/v3/files").build()
    private val uploads: HttpUrl get() = apiBase.newBuilder().addPathSegments("upload/drive/v3/files").build()

    fun about(fields: String, token: String = this.token): Exchange =
        get(apiBase.newBuilder().addPathSegments("drive/v3/about").addQueryParameter("fields", fields).build(), token)

    fun getFile(id: String, fields: String, token: String = this.token): Exchange =
        get(files.newBuilder().addPathSegment(id).addQueryParameter("fields", fields).build(), token)

    fun list(query: String, fields: String, pageSize: Int? = null, pageToken: String? = null): Exchange =
        get(
            files.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", fields)
                // §20.8: My Drive only. The default corpus is the user's own
                // files, and nothing here opts in to shared drives.
                .addQueryParameter("spaces", "drive")
                .apply { pageSize?.let { addQueryParameter("pageSize", it.toString()) } }
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build(),
            token,
        )

    fun createFolder(name: String, parent: String, fields: String = "id,name,mimeType,parents"): Exchange =
        send(
            Request.Builder()
                .url(files.newBuilder().addQueryParameter("fields", fields).build())
                .post(metadata(name, parent, FOLDER_MIME).toRequestBody(JSON)),
        )

    fun delete(id: String): Exchange =
        send(Request.Builder().url(files.newBuilder().addPathSegment(id).build()).delete())

    /**
     * Starts a resumable session and returns the response whose `Location`
     * header is the session URI. Its `upload_id` names the session on its own,
     * which is why a capture pseudonymizes it.
     */
    fun initiateUpload(name: String, parent: String, size: Long, fields: String): Exchange =
        send(
            Request.Builder()
                .url(
                    uploads.newBuilder()
                        .addQueryParameter("uploadType", "resumable")
                        .addQueryParameter("fields", fields)
                        .build(),
                )
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", size.toString())
                .post(metadata(name, parent, null).toRequestBody(JSON)),
        )

    /** One chunk at [offset] of a [total]-byte object. */
    fun putChunk(sessionUri: String, bytes: ByteArray, length: Int, offset: Long, total: Long): Exchange =
        send(
            Request.Builder()
                .url(sessionUri)
                .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                .put(bytes.toRequestBody(OCTET, 0, length))
        )

    /** The §22.5 offset query: an empty PUT asking how much Drive holds. */
    fun queryUpload(sessionUri: String, total: Long): Exchange =
        send(
            Request.Builder()
                .url(sessionUri)
                .header("Content-Range", "bytes */$total")
                .put(ByteArray(0).toRequestBody(null))
        )

    /** §22.2: abandons a session. */
    fun cancelUpload(sessionUri: String): Exchange =
        send(Request.Builder().url(sessionUri).delete())

    private fun get(url: HttpUrl, token: String): Exchange =
        send(Request.Builder().url(url).get(), token = token)

    private fun send(builder: Request.Builder, token: String = this.token): Exchange {
        builder.header("Authorization", "Bearer $token")
        return client.newCall(builder.build()).execute().use { response ->
            Exchange(
                status = response.code,
                headers = response.headers.names().associateWith { response.header(it).orEmpty() },
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun metadata(name: String, parent: String, mimeType: String?): String = buildString {
        append("{\"name\":").append(quote(name))
        append(",\"parents\":[").append(quote(parent)).append(']')
        if (mimeType != null) append(",\"mimeType\":").append(quote(mimeType))
        append('}')
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val FOLDER_MIME = DriveTooling.FOLDER_MIME

        /** A JSON string literal; names and ids are the only things quoted. */
        fun quote(value: String) = buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        /** Drive's query language quotes with `'` and escapes it with `\`. */
        fun queryLiteral(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun parseObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/**
 * §8.3's refresh, with the tooling client.
 *
 * On failure it prints the OAuth error tag and nothing else: a refresh
 * response carries no token when it fails, but its description can quote
 * request parameters, and §26 keeps those out of a terminal.
 * `invalid_grant` gets a sentence of its own because while the OAuth app is in
 * Testing status it is the expected outcome seven days after `drive-auth` ran.
 */
fun refreshAccessToken(client: OkHttpClient, refreshToken: String, tokenEndpoint: String = GoogleOAuth.TOKEN_ENDPOINT): String {
    val form = GoogleOAuth.refreshForm(DriveTooling.DESKTOP_CLIENT_ID, refreshToken, DriveTooling.clientSecret())
    val request = Request.Builder()
        .url(tokenEndpoint)
        .post(Pkce.formBody(form).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
        val body = parseObject(response.body?.string().orEmpty())
        if (!response.isSuccessful) {
            val tag = body?.string("error")
            val hint = if (tag == "invalid_grant") {
                " — the refresh token has expired or been revoked. While the OAuth app is in Testing " +
                    "status that happens seven days after it was minted; run :tools:drive-auth:driveAuth again."
            } else {
                ""
            }
            DriveTooling.fail("Refreshing the access token failed: HTTP ${response.code}${tag?.let { " ($it)" } ?: ""}$hint")
        }
        return body?.string("access_token") ?: DriveTooling.fail("the refresh response carried no access_token")
    }
}

/**
 * The same guard the Dropbox tools put on `DROPBOX_TEST_ROOT`: these tools
 * create and delete, so they refuse to run anywhere but a folder that is
 * recognisably the test root. An id pointing at `root`, at a file, or at some
 * other folder is refused before anything is written.
 */
fun requireTestRoot(drive: DriveHttp, id: String?): String {
    if (id == null) {
        DriveTooling.fail("DRIVE_TEST_ROOT is not set. :tools:drive-auth:driveAuth prints it after minting a token.")
    }
    if (id.equals("root", ignoreCase = true)) {
        DriveTooling.fail("DRIVE_TEST_ROOT is 'root', which is the whole of My Drive. Point it at the test folder.")
    }
    val found = drive.getFile(id, "id,name,mimeType,trashed")
    val file = parseObject(found.body)
    if (!found.ok || file == null) {
        DriveTooling.fail(
            "DRIVE_TEST_ROOT could not be read: HTTP ${found.status}. Under drive.file only folders this " +
                "project created are visible; re-run :tools:drive-auth:driveAuth to find or create it.",
        )
    }
    val ok = file.string("mimeType") == DriveTooling.FOLDER_MIME &&
        file.string("name") == DriveTooling.TEST_ROOT_NAME &&
        file.string("trashed") != "true"
    if (!ok) {
        DriveTooling.fail("DRIVE_TEST_ROOT is not a folder named ${DriveTooling.TEST_ROOT_NAME}; refusing to write there.")
    }
    return id
}

/** The outcome of looking for the test root among files this project created. */
sealed interface TestRoot {
    data class Found(val id: String) : TestRoot
    data class Created(val id: String) : TestRoot

    /** Drive allows same-name siblings (§20.3), so "the" folder can be several. */
    data class Ambiguous(val ids: List<String>) : TestRoot
}

fun findOrCreateTestRoot(drive: DriveHttp): TestRoot {
    val query = "name = ${DriveHttp.queryLiteral(DriveTooling.TEST_ROOT_NAME)} and " +
        "mimeType = ${DriveHttp.queryLiteral(DriveTooling.FOLDER_MIME)} and 'root' in parents and trashed = false"
    val listed = drive.list(query, "files(id)")
    val body = parseObject(listed.body)
    if (!listed.ok || body == null) DriveTooling.fail("Looking for the test root failed: HTTP ${listed.status}")
    val ids = body["files"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.string("id") }
    return when (ids.size) {
        0 -> {
            val created = drive.createFolder(DriveTooling.TEST_ROOT_NAME, "root", fields = "id")
            val id = parseObject(created.body)?.string("id")
            if (!created.ok || id == null) DriveTooling.fail("Creating the test root failed: HTTP ${created.status}")
            TestRoot.Created(id)
        }
        1 -> TestRoot.Found(ids.single())
        else -> TestRoot.Ambiguous(ids)
    }
}
