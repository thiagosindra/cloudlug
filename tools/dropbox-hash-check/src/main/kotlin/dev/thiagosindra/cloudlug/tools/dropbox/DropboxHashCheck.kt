package dev.thiagosindra.cloudlug.tools.dropbox

import dev.thiagosindra.cloudlug.hashing.Hashers
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Checks CloudLug's `DROPBOX_CONTENT_HASH` against the value Dropbox reports
 * for the same bytes (spec §36, §19.4).
 *
 * The v0.1 test vectors were derived from the published algorithm — SHA-256 of
 * the concatenated SHA-256 digests of each 4 MiB block. That catches a coding
 * mistake but not a *misreading*: if the implementation and its vectors share
 * one wrong assumption, both agree and both are wrong. Only bytes that have
 * been through the real service can tell the two apart, which is why §36 puts
 * this at the start of the adapter milestone rather than the end.
 *
 * Three sizes, chosen for where the block hash can go wrong:
 *  - **1 byte** — a single short block; the degenerate case.
 *  - **exactly 4 MiB** — one whole block and no remainder. The boundary case
 *    an off-by-one closes a block early or emits a spurious empty one.
 *  - **~10 MiB** — two full blocks plus a partial, so block order matters.
 *
 * Plus one file already in the account, whose bytes never came from this tool.
 *
 * The token is read from the environment and never written anywhere. Nothing
 * here prints a header or a token, per §26.
 */
private const val CONTENT_HOST = "https://content.dropboxapi.com"
private const val API_HOST = "https://api.dropboxapi.com"
private const val BLOCK = 4 * 1024 * 1024

private val json = Json { ignoreUnknownKeys = true }

private class Dropbox(private val token: String) {
    private val http: HttpClient = HttpClient.newBuilder().build()

    /** Uploads [bytes] to [path] and returns the `content_hash` Dropbox computed. */
    fun upload(path: String, bytes: ByteArray): String {
        val arg = """{"path":${quote(path)},"mode":"overwrite","mute":true}"""
        val request = HttpRequest.newBuilder(URI.create("$CONTENT_HOST/2/files/upload"))
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", arg)
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return contentHashOf(send(request, "upload $path"))
    }

    /** The `content_hash` Dropbox already holds for an existing object. */
    fun metadataHash(path: String): String {
        val request = HttpRequest.newBuilder(URI.create("$API_HOST/2/files/get_metadata"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"path":${quote(path)}}"""))
            .build()
        return contentHashOf(send(request, "get_metadata $path"))
    }

    fun download(path: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create("$CONTENT_HOST/2/files/download"))
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", """{"path":${quote(path)}}""")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("download $path failed: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /**
     * Deletes [path], returning false when there was nothing there.
     *
     * A run that fails before its first upload has no folder to remove, and
     * saying "remove it by hand" about a folder that was never created sends
     * the reader looking for something that does not exist.
     */
    fun deleteIfPresent(path: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create("$API_HOST/2/files/delete_v2"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"path":${quote(path)}}"""))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) return true
        // Dropbox reports a missing path as 409 path_lookup/not_found; 404 shows
        // up on some routes. Neither is a cleanup failure.
        if (response.statusCode() == 404 || (response.statusCode() == 409 && "not_found" in response.body())) {
            return false
        }
        error("delete $path failed: HTTP ${response.statusCode()} ${response.body().take(300)}")
    }

    private fun send(request: HttpRequest, what: String): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The body can echo the path but never the Authorization header.
            error("$what failed: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun contentHashOf(body: String): String =
        json.parseToJsonElement(body).jsonObject["content_hash"]?.jsonPrimitive?.content
            ?: error("no content_hash in response: ${body.take(300)}")

    /** Dropbox paths are user data, so they are escaped as JSON string literals. */
    private fun quote(value: String) = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

internal fun localHash(bytes: ByteArray): String =
    Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
        .apply { update(bytes) }
        .digest()
        .value

private fun report(label: String, local: String, remote: String): Boolean {
    val matched = local.equals(remote, ignoreCase = true)
    println(label)
    println("  local   : $local")
    println("  dropbox : $remote")
    println(if (matched) "  MATCH" else "  MISMATCH")
    println()
    return matched
}

fun main() {
    val token = System.getenv("DROPBOX_TOKEN")?.takeIf { it.isNotBlank() }
    if (token == null) {
        System.err.println(
            "DROPBOX_TOKEN is not set. Export it for this command only; do not put it in a file:\n" +
                "  DROPBOX_TOKEN=... DROPBOX_TEST_PATH=/some/existing/file.jpg \\\n" +
                "    ./gradlew :tools:dropbox-hash-check:validateDropboxHashes --console=plain",
        )
        exitProcess(2)
    }
    val existingPath = System.getenv("DROPBOX_TEST_PATH")?.takeIf { it.isNotBlank() }

    val dropbox = Dropbox(token)
    val random = Random(20260918)
    val folder = "/CloudLug hash check ${Instant.now().toEpochMilli()}"

    val cases = listOf(
        "1 byte" to ByteArray(1) { 0x2a },
        "exactly 4 MiB (one whole block, no remainder)" to random.nextBytes(BLOCK),
        "~10 MiB (two blocks plus a partial)" to random.nextBytes(10 * 1024 * 1024 + 7919),
    )

    var allMatched = true
    try {
        cases.forEachIndexed { index, (label, bytes) ->
            val path = "$folder/case-$index.bin"
            val remote = dropbox.upload(path, bytes)
            allMatched = report("$label — ${bytes.size} bytes", localHash(bytes), remote) && allMatched
        }

        if (existingPath == null) {
            println("DROPBOX_TEST_PATH is not set, so no pre-existing file was checked.")
            println("That case matters most: its bytes never passed through this tool.")
            println()
            allMatched = false
        } else {
            val remote = dropbox.metadataHash(existingPath)
            val bytes = dropbox.download(existingPath)
            allMatched = report(
                "existing file $existingPath — ${bytes.size} bytes",
                localHash(bytes),
                remote,
            ) && allMatched
        }
    } finally {
        // Leave nothing behind, even when a case fails — and say nothing when
        // there was nothing to leave.
        runCatching { dropbox.deleteIfPresent(folder) }
            .onSuccess { removed -> if (removed) println("Cleaned up $folder") }
            .onFailure { System.err.println("Could not delete $folder — remove it by hand: ${it.message}") }
    }

    println(if (allMatched) "All hashes match." else "At least one hash did not match, or a case was skipped.")
    exitProcess(if (allMatched) 0 else 1)
}
