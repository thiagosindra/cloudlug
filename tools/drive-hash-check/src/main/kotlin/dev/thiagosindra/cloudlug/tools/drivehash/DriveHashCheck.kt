package dev.thiagosindra.cloudlug.tools.drivehash

import dev.thiagosindra.cloudlug.hashing.DualHash
import dev.thiagosindra.cloudlug.hashing.DualHashPipeline
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.tools.drive.DriveHttp
import dev.thiagosindra.cloudlug.tools.drive.DriveTooling
import dev.thiagosindra.cloudlug.tools.drive.parseObject
import dev.thiagosindra.cloudlug.tools.drive.refreshAccessToken
import dev.thiagosindra.cloudlug.tools.drive.requireTestRoot
import dev.thiagosindra.cloudlug.tools.drive.runTool
import dev.thiagosindra.cloudlug.tools.drive.string
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Checks CloudLug's MD5 and SHA-256 against what Drive reports for the same
 * bytes (spec §36, §19.4, §21).
 *
 * The algorithms themselves are not in doubt the way Dropbox's block hash was.
 * What is in doubt is everything around them: whether Drive reports
 * `md5Checksum` and `sha256Checksum` at all for a file uploaded under
 * `drive.file`, whether it reports them **in the finishing response** or only
 * later (§21 step 1 versus step 2), in what case and encoding, and whether the
 * pipeline still agrees after being checkpointed and restored at every chunk
 * boundary the way the engine does across process death. Only a real upload
 * answers those.
 *
 * The bytes go up the way the adapter will send them — a resumable session,
 * 8 MiB chunks (§15), each non-final chunk answered by 308 with a `Range` this
 * tool checks — so a protocol misreading shows up here, before the adapter is
 * written on top of it.
 *
 * Sizes, chosen for where the upload or the hash can go wrong:
 *  - **1 byte** — the degenerate case.
 *  - **exactly 256 KiB** — Drive's chunk granularity, as one final chunk.
 *  - **exactly 8 MiB** — one whole CloudLug chunk with no remainder; a loop
 *    that sends an empty trailing chunk or never sends the final one shows here.
 *  - **~10 MiB** — one full chunk, then a final chunk that is not a multiple of
 *    256 KiB, with a checkpoint-and-restore between them.
 *  - **a file you name** (`DRIVE_HASH_CHECK_FILE`), a local path whose bytes
 *    this tool did not generate. See the README for why it cannot be a file
 *    already in Drive.
 *
 * The token is read from the environment and never written anywhere. Nothing
 * here prints a header or a token (§26).
 */
const val CHUNK = 8 * 1024 * 1024

private const val FIELDS = "id,size,md5Checksum,sha256Checksum"

fun main() = runTool("validateDriveHashes") {
    val refreshToken = DriveTooling.env("DRIVE_REFRESH_TOKEN")
        ?: DriveTooling.fail("DRIVE_REFRESH_TOKEN is not set. Mint one with :tools:drive-auth:driveAuth.")
    val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()
    val drive = DriveHttp(client, refreshAccessToken(client, refreshToken))
    val root = requireTestRoot(drive, DriveTooling.env("DRIVE_TEST_ROOT"))

    val random = Random(20260930)
    val cases = mutableListOf(
        Case.generated("1 byte", ByteArray(1) { 0x2a }),
        Case.generated("exactly 256 KiB (Drive's granularity, one final chunk)", random.nextBytes(256 * 1024)),
        Case.generated("exactly 8 MiB (one whole chunk, no remainder)", random.nextBytes(CHUNK)),
        Case.generated("~10 MiB (a whole chunk, then an unaligned final one)", random.nextBytes(10 * 1024 * 1024 + 7919)),
    )
    val named = DriveTooling.env("DRIVE_HASH_CHECK_FILE")?.let(::File)
    if (named != null) {
        if (!named.isFile || named.length() == 0L) DriveTooling.fail("DRIVE_HASH_CHECK_FILE must name a non-empty file.")
        // The label says "named file", never the name: it is the caller's, and
        // this output gets pasted into pull requests.
        cases += Case("the named local file", named.length()) { named.inputStream() }
    }

    val folderResponse = drive.createFolder("hash-check-${System.currentTimeMillis()}", root, fields = "id")
    val folder = parseObject(folderResponse.body)?.string("id")
        ?: DriveTooling.fail("Could not create the run's folder: HTTP ${folderResponse.status}")

    val outcomes = mutableListOf<Outcome>()
    try {
        cases.forEachIndexed { index, case -> outcomes += check(drive, folder, "case-$index.bin", case) }
    } finally {
        val deleted = drive.delete(folder)
        if (deleted.ok) println("Cleaned up the run's folder.") else System.err.println("Could not delete the run's folder (HTTP ${deleted.status}); remove it by hand.")
    }

    println()
    if (named == null) {
        println("DRIVE_HASH_CHECK_FILE is not set, so no file of your own was checked.")
    }
    val notReturned = outcomes.flatMap { it.fields }.filter { it.verdict == Verdict.NOT_RETURNED }.map { it.label }.distinct()
    if (notReturned.isNotEmpty()) println("Not returned by Drive in at least one case: ${notReturned.joinToString(", ")}")
    val passed = named != null && outcomes.all { it.passed }
    println(if (passed) "All hashes match." else "At least one check failed, or a case was skipped.")
    exitProcess(if (passed) 0 else 1)
}

class Case(val label: String, val size: Long, val open: () -> InputStream) {
    companion object {
        fun generated(label: String, bytes: ByteArray) = Case(label, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}

enum class Verdict { MATCH, MISMATCH, NOT_RETURNED }

data class Field(val label: String, val local: String, val remote: String?) {
    val verdict: Verdict = when {
        remote == null -> Verdict.NOT_RETURNED
        remote.equals(local, ignoreCase = true) -> Verdict.MATCH
        else -> Verdict.MISMATCH
    }
}

/**
 * A case passes when nothing disagrees and MD5 — §19.4's native hash for
 * Drive — was reported at least once. A missing SHA-256 is reported, not
 * failed: §19.4 says compare it "when returned", and whether it is returned is
 * one of the things this run exists to find out.
 */
data class Outcome(val fields: List<Field>, val protocolProblems: List<String>) {
    val passed: Boolean
        get() = protocolProblems.isEmpty() &&
            fields.none { it.verdict == Verdict.MISMATCH } &&
            fields.any { it.label.startsWith("md5") && it.verdict == Verdict.MATCH }
}

/**
 * Feeds the §19.4 pipeline the way the engine does after every acknowledged
 * chunk: checkpoint, then carry on from the *restored* checkpoint rather than
 * the live hasher. If a checkpoint loses state, the digest shows it.
 */
class ChunkedHash {
    private var pipeline = DualHashPipeline(HashAlgorithm.MD5)

    fun feed(buffer: ByteArray, length: Int) {
        pipeline.update(buffer, 0, length)
        pipeline = DualHashPipeline.restore(HashAlgorithm.MD5, pipeline.checkpoint())
    }

    fun finish(): DualHash = pipeline.finish()
}

private fun check(drive: DriveHttp, folder: String, name: String, case: Case): Outcome {
    println("${case.label} — ${case.size} bytes")
    val problems = mutableListOf<String>()

    val started = drive.initiateUpload(name, folder, case.size, FIELDS)
    val session = started.header("Location")
        ?: DriveTooling.fail("initiating the upload returned HTTP ${started.status} and no session URI")

    val hash = ChunkedHash()
    val buffer = ByteArray(CHUNK)
    var offset = 0L
    var finished: String? = null
    case.open().use { input ->
        while (offset < case.size) {
            val wanted = minOf(CHUNK.toLong(), case.size - offset).toInt()
            val read = input.readNBytes(buffer, 0, wanted)
            if (read != wanted) DriveTooling.fail("the source ended early at byte ${offset + read}; did it change?")
            hash.feed(buffer, read)

            val answer = drive.putChunk(session, buffer, read, offset, case.size)
            offset += read
            val last = offset == case.size
            if (last) {
                if (answer.status !in listOf(200, 201)) problems += "final chunk answered HTTP ${answer.status}, not 200/201"
                finished = answer.body
            } else {
                // §22.5 rests on this: 308, and a Range naming exactly what arrived.
                if (answer.status != 308) problems += "chunk ending at $offset answered HTTP ${answer.status}, not 308"
                val range = answer.header("Range")
                if (range != "bytes=0-${offset - 1}") problems += "after $offset bytes Drive's Range was '${range ?: "absent"}'"
            }
        }
    }
    val local = hash.finish()

    val finish = finished?.let(::parseObject)
    val id = finish?.string("id") ?: DriveTooling.fail("the finishing response carried no file id")
    val fetched = parseObject(drive.getFile(id, FIELDS).body)

    val sizeReported = finish.string("size") ?: fetched?.string("size")
    if (sizeReported != case.size.toString()) problems += "Drive reports size ${sizeReported ?: "absent"}"

    val fields = listOf(
        Field("md5Checksum in the finishing response", local.destinationNative!!.value, finish.string("md5Checksum")),
        Field("md5Checksum from files.get", local.destinationNative!!.value, fetched?.string("md5Checksum")),
        Field("sha256Checksum in the finishing response", local.sha256.value, finish.string("sha256Checksum")),
        Field("sha256Checksum from files.get", local.sha256.value, fetched?.string("sha256Checksum")),
    )
    fields.forEach { println("  %-44s %s".format(it.label, it.verdict)) }
    println("  local md5    : ${local.destinationNative!!.value}")
    println("  local sha256 : ${local.sha256.value}")
    problems.forEach { println("  PROTOCOL: $it") }
    println()
    return Outcome(fields, problems)
}
