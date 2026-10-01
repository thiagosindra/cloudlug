package dev.thiagosindra.cloudlug.tools.drivecapture

import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.googledrive.GoogleOAuth
import dev.thiagosindra.cloudlug.tools.drive.DriveHttp
import dev.thiagosindra.cloudlug.tools.drive.DriveTooling
import dev.thiagosindra.cloudlug.tools.drive.Exchange
import dev.thiagosindra.cloudlug.tools.drive.parseObject
import dev.thiagosindra.cloudlug.tools.drive.refreshAccessToken
import dev.thiagosindra.cloudlug.tools.drive.requireTestRoot
import dev.thiagosindra.cloudlug.tools.drive.runTool
import dev.thiagosindra.cloudlug.tools.drive.string
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalDate

/**
 * Records what Drive really sends, for the adapter's offline tests (docs/testing.md
 * rule 1) — before the adapter exists, so it is written against these rather
 * than against a reading of the documentation.
 *
 * It builds a small tree of its own under `DRIVE_TEST_ROOT`, drives every route
 * the adapter will use, provokes every error it can without harming the
 * account, redacts, refuses to write if anything real survived, and deletes
 * what it made.
 *
 * Output: one body file per exchange, and `manifest.json` recording each
 * exchange's route, status and the few response headers the adapter reads
 * (`Location`, `Range`, `Retry-After`, `Content-Type`). A resumable upload's
 * answer is mostly status and headers; a fixture of the body alone would
 * record almost nothing of it.
 */
fun main() = runTool("captureDriveFixtures") {
    val refreshToken = DriveTooling.env("DRIVE_REFRESH_TOKEN")
        ?: DriveTooling.fail("DRIVE_REFRESH_TOKEN is not set. Mint one with :tools:drive-auth:driveAuth.")
    val outputDir = File(
        System.getProperty("cloudlug.fixtures.dir")
            ?: DriveTooling.fail("cloudlug.fixtures.dir is not set; run this through :tools:drive-capture:captureDriveFixtures"),
    )
    val client = OkHttpClient()
    val drive = DriveHttp(client, refreshAccessToken(client, refreshToken))
    val root = requireTestRoot(drive, DriveTooling.env("DRIVE_TEST_ROOT"))

    val capture = Capture(client, drive, refreshToken)
    capture.redaction.register(root)
    println("Capturing Drive fixtures under the test root")
    val workspace = capture.createWorkspace(root)
    try {
        capture.run(workspace)
    } finally {
        capture.cleanUp(workspace)
    }
    capture.writeTo(outputDir)
}

/** One recorded exchange, before redaction. */
private data class Recorded(
    val name: String,
    val method: String,
    val route: String,
    val exchange: Exchange,
    val note: String,
)

private class Capture(
    private val client: OkHttpClient,
    private val drive: DriveHttp,
    private val refreshToken: String,
) {
    val redaction = DriveRedaction()
    private val recorded = mutableListOf<Recorded>()

    fun createWorkspace(root: String): String {
        val created = record("files_create_folder_200", "POST", "/drive/v3/files", drive.createFolder("capture-${System.currentTimeMillis()}", root, FOLDER_FIELDS), "the workspace, under the test root")
        return idOf(created)
    }

    fun run(workspace: String) {
        record("about_get_200", "GET", "/drive/v3/about", drive.about(ABOUT_FIELDS), "storageQuota for §20.7; user for §7's account row")

        // Containment, as for Dropbox: a folder in the workspace, a file in
        // that, so a listing test can tell a parent from a child.
        val nested = idOf(record("files_create_folder_nested_200", "POST", "/drive/v3/files", drive.createFolder("nested", workspace, FOLDER_FIELDS), "a folder inside the workspace"))
        val file = resumableLifecycle(nested)

        // Two same-name siblings: §19.3's multiple-match CONFLICT, and three
        // children in the workspace so pageSize=2 has a real second page —
        // Dropbox's paging fixture was degenerate for want of exactly this.
        repeat(2) { upload(workspace, "duplicate.bin", ByteArray(3) { (it + 1).toByte() }) }

        val page1 = record(
            "files_list_by_parent_page1_200", "GET", "/drive/v3/files",
            drive.list(childrenOf(workspace), LIST_FIELDS, pageSize = 2), "pageSize=2 over three children",
        )
        val pageToken = parseObject(page1.body)?.string("nextPageToken")
        if (pageToken == null) {
            println("  (no nextPageToken: the second page was not captured)")
        } else {
            record(
                "files_list_by_parent_page2_200", "GET", "/drive/v3/files",
                drive.list(childrenOf(workspace), LIST_FIELDS, pageSize = 2, pageToken = pageToken), "the last page: no nextPageToken",
            )
        }
        record("files_list_nested_200", "GET", "/drive/v3/files", drive.list(childrenOf(nested), LIST_FIELDS), "one level below the workspace")
        record(
            "files_list_by_name_duplicates_200", "GET", "/drive/v3/files",
            drive.list(named("duplicate.bin", workspace), LIST_FIELDS), "§19.3: two siblings with one name",
        )
        record(
            "files_list_by_name_none_200", "GET", "/drive/v3/files",
            drive.list(named("absent.bin", workspace), LIST_FIELDS), "§19.3: no match",
        )

        record("files_get_file_200", "GET", "/drive/v3/files/{id}", drive.getFile(file, FILE_FIELDS), "the checksums §21 step 2 falls back to")
        record("files_get_folder_200", "GET", "/drive/v3/files/{id}", drive.getFile(nested, FILE_FIELDS))

        // Deletion, then everything that can go wrong with an id that no
        // longer exists.
        record("files_delete_204", "DELETE", "/drive/v3/files/{id}", drive.delete(file), "the body is empty; the status is the answer")
        record("files_get_not_found_404", "GET", "/drive/v3/files/{id}", drive.getFile(file, FILE_FIELDS), "the file just deleted")
        record(
            "files_create_folder_parent_not_found_404", "POST", "/drive/v3/files",
            drive.createFolder("orphan", file, FOLDER_FIELDS), "prepareDestination under a parent that is gone",
        )
        record("files_list_bad_query_400", "GET", "/drive/v3/files", drive.list("this is not a query", LIST_FIELDS), "a malformed q")
        record("files_get_invalid_token_401", "GET", "/drive/v3/files/{id}", drive.getFile(nested, FILE_FIELDS, token = "not-a-real-token"), "§23 AUTH_REQUIRED")
        record("token_refresh_invalid_grant_400", "POST", "/token", refreshWith("not-a-real-refresh-token"), "§23: what a Testing-status token looks like after seven days")
    }

    /**
     * Every step of a resumable upload the adapter will take, on a file of
     * 256 KiB plus a little, sent as one aligned chunk and one final one.
     */
    private fun resumableLifecycle(parent: String): String {
        val total = CHUNK + 1000L
        val bytes = ByteArray(total.toInt()) { (it % 251).toByte() }

        val started = record("upload_initiate_200", "POST", "/upload/drive/v3/files?uploadType=resumable", drive.initiateUpload("captured.bin", parent, total, FILE_FIELDS), "the session URI is the Location header")
        val session = started.header("Location") ?: DriveTooling.fail("initiating an upload returned no Location")
        record("upload_status_nothing_received_308", "PUT", "{session}", drive.queryUpload(session, total), "no Range header: nothing has arrived")
        record("upload_chunk_308", "PUT", "{session}", drive.putChunk(session, bytes, CHUNK, 0, total), "Range names what arrived")
        record("upload_status_308", "PUT", "{session}", drive.queryUpload(session, total), "§22.5's offset query")
        // What §22.5's recovery pass does after process death: send from byte
        // zero again to a session that already holds the bytes. Dropbox
        // answered this with an error (status.md, defect 2); record what Drive
        // does rather than assume.
        record("upload_chunk_resent_from_zero", "PUT", "{session}", drive.putChunk(session, bytes, CHUNK, 0, total), "a chunk the session already holds")
        val finished = record(
            "upload_finish_200", "PUT", "{session}",
            drive.putChunk(session, bytes.copyOfRange(CHUNK, total.toInt()), (total - CHUNK).toInt(), CHUNK.toLong(), total),
            "the final chunk; fields asked for at initiation",
        )
        record("upload_status_after_finish", "PUT", "{session}", drive.queryUpload(session, total), "querying a session that has completed")

        // A second session for the two failures: a non-final chunk that is not
        // a multiple of 256 KiB, then §22.2's abort and what the session says
        // afterwards.
        val unaligned = drive.initiateUpload("unaligned.bin", parent, 600_000, FILE_FIELDS).header("Location")
            ?: DriveTooling.fail("initiating the second upload returned no Location")
        record("upload_chunk_unaligned", "PUT", "{session}", drive.putChunk(unaligned, ByteArray(300_000), 300_000, 0, 600_000), "a non-final chunk of 300000 bytes")
        record("upload_cancel", "DELETE", "{session}", drive.cancelUpload(unaligned), "§22.2: abandoning a session")
        record("upload_status_cancelled", "PUT", "{session}", drive.queryUpload(unaligned, 600_000), "querying a session after abandoning it")

        return idOf(finished)
    }

    /** An upload that is scaffolding for another capture, not itself recorded. */
    private fun upload(parent: String, name: String, bytes: ByteArray) {
        val session = drive.initiateUpload(name, parent, bytes.size.toLong(), "id").header("Location")
            ?: DriveTooling.fail("initiating an upload returned no Location")
        val done = drive.putChunk(session, bytes, bytes.size, 0, bytes.size.toLong())
        if (!done.ok) DriveTooling.fail("uploading $name failed: HTTP ${done.status}")
        parseObject(done.body)?.string("id")?.let(redaction::register)
    }

    private fun refreshWith(token: String): Exchange {
        val form = GoogleOAuth.refreshForm(DriveTooling.DESKTOP_CLIENT_ID, token, DriveTooling.clientSecret())
        val request = Request.Builder()
            .url(GoogleOAuth.TOKEN_ENDPOINT)
            .post(Pkce.formBody(form).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            Exchange(response.code, response.headers.names().associateWith { response.header(it).orEmpty() }, response.body?.string().orEmpty())
        }
    }

    fun cleanUp(workspace: String) {
        val deleted = drive.delete(workspace)
        if (!deleted.ok) println("Could not delete the workspace (HTTP ${deleted.status}); remove it by hand.")
    }

    /**
     * Redacts everything, checks nothing real survived, then writes. The
     * check runs over every file before the first is written, so a leak
     * leaves the directory as it was rather than half-updated.
     */
    fun writeTo(directory: File) {
        // The refresh token is sent only to the token endpoint and never
        // echoed back. If it ever were, redacting it would hide the problem;
        // refusing is the only acceptable outcome.
        if (recorded.any { r -> refreshToken in r.exchange.body || r.exchange.headers.values.any { refreshToken in it } }) {
            DriveTooling.fail("Refusing to write fixtures: a response echoed the refresh token. Nothing was written.")
        }
        recorded.forEach { r ->
            redaction.harvest(r.exchange.body)
            r.exchange.headers.values.forEach(redaction::harvest)
        }

        val files = recorded.map { r ->
            val extension = if (parseObject(r.exchange.body) != null) "json" else "txt"
            val kept = r.exchange.headers.filterKeys { key -> KEPT_HEADERS.any { it.equals(key, ignoreCase = true) } }
                .mapValues { redaction.redact(it.value) }
            Written(r, "${r.name}.$extension", redaction.redact(r.exchange.body), kept)
        }
        val manifest = manifest(files)
        val leaks = (files.map { it.body } + manifest).flatMap(redaction::leaks).distinct()
        if (leaks.isNotEmpty()) {
            DriveTooling.fail("Refusing to write fixtures: redaction left ${leaks.joinToString(", ")}. Nothing was written.")
        }

        directory.mkdirs()
        files.forEach { File(directory, it.file).writeText(it.body) }
        File(directory, "manifest.json").writeText(manifest)
        File(directory, "README.md").writeText(readme(files))
        println("Wrote ${files.size} fixtures to $directory (${redaction.replacements} values replaced)")
    }

    private data class Written(val recorded: Recorded, val file: String, val body: String, val headers: Map<String, String>)

    private fun manifest(files: List<Written>): String = buildString {
        appendLine("[")
        files.forEachIndexed { index, w ->
            append("  {\"name\": ").append(DriveHttp.quote(w.recorded.name))
            append(", \"method\": ").append(DriveHttp.quote(w.recorded.method))
            append(", \"route\": ").append(DriveHttp.quote(w.recorded.route))
            append(", \"status\": ").append(w.recorded.exchange.status)
            append(", \"body\": ").append(DriveHttp.quote(w.file))
            append(", \"headers\": {")
            append(w.headers.entries.sortedBy { it.key.lowercase() }.joinToString(", ") { (k, v) -> "${DriveHttp.quote(k.lowercase())}: ${DriveHttp.quote(v)}" })
            append("}}")
            appendLine(if (index < files.lastIndex) "," else "")
        }
        appendLine("]")
    }

    private fun readme(files: List<Written>): String = buildString {
        appendLine("# Captured Google Drive responses")
        appendLine()
        appendLine("Written by `./gradlew :tools:drive-capture:captureDriveFixtures`. Do not edit by hand:")
        appendLine("a fixture is only worth having if it is what the service actually sent")
        appendLine("(`docs/testing.md` rule 1).")
        appendLine()
        appendLine("`manifest.json` records each exchange's status and the response headers the")
        appendLine("adapter reads (`Location`, `Range`, `Retry-After`, `Content-Type`); the body is")
        appendLine("in the file it names. A resumable upload answers mostly in status and headers.")
        appendLine()
        appendLine("File and folder ids, `permissionId`, `nextPageToken`s and the `upload_id` in each")
        appendLine("session URI are replaced with stable pseudonyms of the same length and alphabet,")
        appendLine("everywhere they occur — including inside error messages and `Location`. The")
        appendLine("account's email address, display name and photo link are replaced with invented")
        appendLine("values. Checksums, sizes, timestamps and `version` are kept verbatim. Every name")
        appendLine("was created by the capture tool. The tool refuses to write if any real value")
        appendLine("survives redaction.")
        appendLine()
        appendLine("| Body | Method | Route | Status | Provenance | Note |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        files.sortedBy { it.recorded.name }.forEach {
            appendLine("| `${it.file}` | ${it.recorded.method} | `${it.recorded.route}` | ${it.recorded.exchange.status} | captured ${LocalDate.now()} | ${it.recorded.note} |")
        }
        appendLine()
        appendLine("## Not capturable")
        appendLine()
        appendLine("These cannot be provoked on demand without harming the account. They are absent")
        appendLine("rather than invented, and the §23 tests that need them say so:")
        appendLine()
        appendLine("- 403 `userRateLimitExceeded` / `rateLimitExceeded` / `dailyLimitExceeded` —")
        appendLine("  needs sustained throttling of the project's quota.")
        appendLine("- 403 `storageQuotaExceeded` — needs a full account.")
        appendLine("- 429 and 5xx — need Google to be throttling or failing.")
        appendLine("- A session URI past its one-week expiry — needs a week.")
    }

    private fun record(name: String, method: String, route: String, exchange: Exchange, note: String = ""): Exchange {
        recorded += Recorded(name, method, route, exchange, note)
        println("  ${exchange.status}  $name")
        return exchange
    }

    private fun idOf(exchange: Exchange): String =
        parseObject(exchange.body)?.string("id") ?: DriveTooling.fail("expected an id, got HTTP ${exchange.status}")

    private fun childrenOf(parent: String) = "${DriveHttp.queryLiteral(parent)} in parents and trashed = false"

    private fun named(name: String, parent: String) = "name = ${DriveHttp.queryLiteral(name)} and ${childrenOf(parent)}"

    private companion object {
        /** Drive's granularity, as the first chunk: the smallest aligned non-final chunk. */
        const val CHUNK = 256 * 1024

        const val FOLDER_FIELDS = "id,name,mimeType,parents"
        const val FILE_FIELDS = "id,name,mimeType,parents,size,md5Checksum,sha256Checksum,modifiedTime,createdTime,version,trashed"
        const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"
        const val ABOUT_FIELDS = "user(displayName,emailAddress,permissionId,photoLink),storageQuota"

        val KEPT_HEADERS = listOf("Location", "Range", "Retry-After", "Content-Type")
    }
}
