package dev.thiagosindra.cloudlug.tools.capture

import dev.thiagosindra.cloudlug.provider.dropbox.DropboxOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Records what Dropbox really sends, for the offline tests to run against.
 *
 * `docs/testing.md` rule 1 asks every adapter to ship an offline test driven by
 * **recorded** provider responses, and is explicit that a hand-written
 * approximation does not count. The reason is on the record twice: the
 * flattened error union that `DropboxErrors` was first written against was a
 * reconstruction and was wrong in a way that broke the parser, and
 * `upload_session/finish` returns a struct with no `.tag` where every other
 * route returns a union member — a difference no amount of reading the
 * documentation made obvious, and which failed every file in the first real
 * transfer (ADR-0030).
 *
 * So this creates a small tree of its own under `DROPBOX_TEST_ROOT`, provokes
 * every shape the adapter can meet, writes the bodies down with the account's
 * identifiers replaced, and deletes what it made.
 *
 * It is not a test. It needs a live token, the network, and the right to create
 * and delete objects, so it never runs in CI or as part of `build`.
 */
fun main() {
    val refreshToken = System.getenv("DROPBOX_REFRESH_TOKEN")?.takeIf { it.isNotBlank() } ?: fail(
        "DROPBOX_REFRESH_TOKEN is not set. Mint one with :tools:dropbox-auth:dropboxAuth.",
    )
    val root = System.getenv("DROPBOX_TEST_ROOT")?.trim().orEmpty().ifBlank { "/cloudlug-contract-tests" }
    // The same guard the live contract suite uses. This tool creates and
    // deletes; a run pointed at the account root would be a loaded gun.
    if (!root.startsWith("/") || root.trimEnd('/').length <= 1) {
        fail("DROPBOX_TEST_ROOT is '$root', which is the account root. Point it at a folder of its own.")
    }
    val outputDir = File(
        System.getProperty("cloudlug.fixtures.dir")
            ?: fail("cloudlug.fixtures.dir is not set; run this through :tools:dropbox-capture:captureDropboxFixtures"),
    )

    val client = OkHttpClient()
    val capture = Capture(client, accessToken(client, refreshToken), root, Redaction(root))

    println("Capturing Dropbox fixtures under $root")
    val workspace = "$root/capture-${System.currentTimeMillis()}"
    try {
        capture.run(workspace)
    } finally {
        capture.cleanUp(workspace)
    }

    capture.writeTo(outputDir)
    println("Wrote ${capture.recorded.size} fixtures to $outputDir (${capture.redaction.replacements} ids replaced)")
}

/** One recorded exchange: enough for a test to replay it and to know what it is. */
data class Recorded(val name: String, val route: String, val status: Int, val body: String, val note: String)

private class Capture(
    private val client: OkHttpClient,
    private val token: String,
    private val root: String,
    val redaction: Redaction,
) {
    val recorded = mutableListOf<Recorded>()

    fun run(workspace: String) {
        // A tree with containment in it, which is what the enumeration tests
        // need: the folder, a folder inside it, and a file inside that.
        record("create_folder_v2_200", "/2/files/create_folder_v2", rpc("/2/files/create_folder_v2", path(workspace)))
        val inner = "$workspace/nested"
        record("create_folder_v2_conflict_409", "/2/files/create_folder_v2", rpc("/2/files/create_folder_v2", path(workspace)), "a second create of the same folder")
        rpc("/2/files/create_folder_v2", path(inner))

        val file = "$inner/captured.bin"
        uploadSession(file)

        record("list_folder_200", "/2/files/list_folder", rpc("/2/files/list_folder", path(workspace)))
        // The level below, so the fixtures carry a parent and a child rather
        // than one flat listing. An enumeration test built on a single level
        // cannot tell a walk that descends from one that does not.
        record("list_folder_nested_200", "/2/files/list_folder", rpc("/2/files/list_folder", path(inner)), "the folder inside the workspace")
        // limit:1 forces a second page, so `continue` is a real paged response
        // rather than one invented to look like one.
        val firstPage = rpc("/2/files/list_folder", """{"path":"${esc(workspace)}","limit":1}""")
        record("list_folder_paged_200", "/2/files/list_folder", firstPage, "limit=1, so has_more is true")
        cursorOf(firstPage.body)?.let { cursor ->
            record("list_folder_continue_200", "/2/files/list_folder/continue", rpc("/2/files/list_folder/continue", """{"cursor":"${esc(cursor)}"}"""))
        }

        record("get_metadata_file_200", "/2/files/get_metadata", rpc("/2/files/get_metadata", path(file)))
        record("get_space_usage_200", "/2/users/get_space_usage", rpcWithoutArgument("/2/users/get_space_usage"))

        // Every error the adapter's §23 mapping claims to handle and that can
        // be provoked without harming the account.
        record("get_metadata_not_found_409", "/2/files/get_metadata", rpc("/2/files/get_metadata", path("$workspace/does-not-exist")))
        record("list_folder_not_folder_409", "/2/files/list_folder", rpc("/2/files/list_folder", path(file)), "listing a file")
        record("get_metadata_malformed_path_400", "/2/files/get_metadata", rpc("/2/files/get_metadata", """{"path":"not-a-path"}"""))
        record("get_metadata_invalid_token_401", "/2/files/get_metadata", rpc("/2/files/get_metadata", path(file), token = "not-a-real-token"))
    }

    /** start, append, finish — and the two failures the upload path can meet. */
    private fun uploadSession(destination: String) {
        val started = content("/2/files/upload_session/start", """{"close":false}""", ByteArray(0))
        record("upload_session_start_200", "/2/files/upload_session/start", started)
        val sessionId = started.body.let { runCatching { json.parseToJsonElement(it).jsonObject["session_id"]?.jsonPrimitive?.content }.getOrNull() }
            ?: fail("upload_session/start returned no session_id")

        val payload = ByteArray(1024) { (it % 251).toByte() }
        val cursor = """{"session_id":"${esc(sessionId)}","offset":0}"""
        record(
            "upload_session_append_v2_200",
            "/2/files/upload_session/append_v2",
            content("/2/files/upload_session/append_v2", """{"cursor":$cursor,"close":false}""", payload),
            "the body is empty; the status is the whole answer",
        )

        // §22.5's recovery signal: append at an offset that is not where the
        // session really is, and read the offset out of the error.
        record(
            "upload_session_incorrect_offset_409",
            "/2/files/upload_session/append_v2",
            content("/2/files/upload_session/append_v2", """{"cursor":{"session_id":"${esc(sessionId)}","offset":0},"close":false}""", ByteArray(0)),
            "appending at an offset the session has passed",
        )

        val commit = """{"cursor":{"session_id":"${esc(sessionId)}","offset":${payload.size}},"commit":{"path":"${esc(destination)}","mode":"add","autorename":false,"mute":true}}"""
        // The one that mattered: a FileMetadata struct, with no `.tag`.
        record("upload_session_finish_200", "/2/files/upload_session/finish", content("/2/files/upload_session/finish", commit, ByteArray(0)))
    }

    fun cleanUp(workspace: String) {
        runCatching { rpc("/2/files/delete_v2", path(workspace)) }
            .onFailure { println("Could not delete $workspace: ${it.javaClass.simpleName}") }
    }

    fun writeTo(directory: File) {
        directory.mkdirs()
        recorded.forEach { File(directory, "${it.name}.json").writeText(it.body) }
        File(directory, "README.md").writeText(readme(directory))
    }

    // ------------------------------------------------------------------ http

    private fun record(name: String, route: String, response: Response, note: String = "") {
        recorded += Recorded(name, route, response.status, redaction.redact(response.body), note)
        println("  ${response.status}  $name")
    }

    private fun rpc(route: String, body: String, token: String = this.token): Response =
        send(
            Request.Builder()
                .url("$API_HOST$route")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON_TYPE)),
        )

    private fun rpcWithoutArgument(route: String): Response =
        send(
            Request.Builder()
                .url("$API_HOST$route")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null)),
        )

    private fun content(route: String, arg: String, payload: ByteArray): Response =
        send(
            Request.Builder()
                .url("$CONTENT_HOST$route")
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", arg)
                .post(payload.toRequestBody(OCTET_TYPE)),
        )

    private fun send(builder: Request.Builder): Response =
        client.newCall(builder.build()).execute().use { Response(it.code, it.body?.string().orEmpty()) }

    private fun path(value: String) = """{"path":"${esc(value)}"}"""

    private fun cursorOf(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["cursor"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun readme(directory: File): String {
        val captured = recorded.associateBy { "${it.name}.json" }
        val leftovers = directory.listFiles()
            ?.filter { it.name.endsWith(".json") && it.name !in captured }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

        return buildString {
            appendLine("# Captured Dropbox responses")
            appendLine()
            appendLine("Written by `./gradlew :tools:dropbox-capture:captureDropboxFixtures`. Do not edit by hand:")
            appendLine("a fixture is only worth having if it is what the service actually sent")
            appendLine("(`docs/testing.md` rule 1).")
            appendLine()
            appendLine("Object ids, account ids and paths are replaced with stable pseudonyms — the same")
            appendLine("real id maps to the same pseudonym across every file, so the containment the")
            appendLine("enumeration tests check survives. Upload session ids and `list_folder`")
            appendLine("cursors are replaced too, keeping their original length and character set so")
            appendLine("the bodies still parse the way the captured ones did. `rev`, `content_hash`")
            appendLine("and timestamps are kept verbatim: neither names a person or a place, and the")
            appendLine("hashes are what §21's verification asserts against. Every name in these files")
            appendLine("was created by the capture tool, so no real filename appears.")
            appendLine()
            appendLine("JSON cannot carry an HTTP status, and §23 maps on the status as well as the")
            appendLine("body, so the status is recorded here.")
            appendLine()
            appendLine("| File | Route | Status | Provenance | Note |")
            appendLine("| --- | --- | --- | --- | --- |")
            recorded.sortedBy { it.name }.forEach {
                appendLine("| `${it.name}.json` | `${it.route}` | ${it.status} | captured ${LocalDate.now()} | ${it.note} |")
            }
            if (leftovers.isNotEmpty()) {
                appendLine()
                appendLine("## Not captured by the last run")
                appendLine()
                appendLine("These files are in this directory but no capture run produced them, so nothing")
                appendLine("here vouches for their shape. Either the tool no longer provokes that case or")
                appendLine("they were written by hand; see git history and treat them as unverified.")
                appendLine()
                leftovers.forEach { appendLine("- `$it`") }
            }
            appendLine()
            appendLine("## Not capturable")
            appendLine()
            appendLine("Some responses cannot be provoked without harming the account or waiting on")
            appendLine("Dropbox's own behaviour. They are absent rather than invented:")
            appendLine()
            appendLine("- `insufficient_space` (§23 `DESTINATION_STORAGE_FULL`) — needs a full account.")
            appendLine("  The one real body is kept in `../errors/`, from a genuine failure.")
            appendLine("- 429 with `Retry-After` — needs sustained throttling.")
            appendLine("- 5xx — needs Dropbox to be having a bad day.")
        }
    }

    private companion object {
        const val API_HOST = "https://api.dropboxapi.com"
        const val CONTENT_HOST = "https://content.dropboxapi.com"
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_TYPE = "application/octet-stream".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}

private data class Response(val status: Int, val body: String)

/** JSON string escaping for the small values this tool interpolates. */
private fun esc(raw: String) = raw.replace("\\", "\\\\").replace("\"", "\\\"")

private val json = Json { ignoreUnknownKeys = true }

private fun accessToken(client: OkHttpClient, refreshToken: String): String {
    val form = FormBody.Builder().apply {
        DropboxOAuth.refreshForm(refreshToken).forEach { (key, value) -> add(key, value) }
    }.build()
    client.newCall(Request.Builder().url(DropboxOAuth.TOKEN_ENDPOINT).post(form).build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            // The tag, never the body: a failed refresh carries an error name,
            // and §26 keeps the rest of it out of the terminal.
            val tag = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            fail("Refreshing the access token failed: HTTP ${response.code}${tag?.let { " ($it)" } ?: ""}")
        }
        return runCatching { json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.content }
            .getOrNull() ?: fail("the refresh response carried no access_token")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("captureDropboxFixtures: $message")
    exitProcess(1)
}
