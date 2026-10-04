package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Destination account quota, as far as the provider reports it (spec §20.7). */
data class StorageQuota(
    val totalBytes: Long?,
    val usedBytes: Long?,
    val availableBytes: Long?,
) {
    /**
     * Whether [bytes] provably do not fit. Unknown quota is not treated as
     * insufficient — the preflight check refuses to start only on evidence
     * (spec §2.5, §20.7).
     */
    fun cannotFit(bytes: Long): Boolean {
        val available = availableBytes ?: totalBytes?.let { total -> usedBytes?.let { total - it } }
        return available != null && available < bytes
    }
}

/**
 * An open byte stream from the source provider.
 *
 * Reads are suspending so adapters can back onto non-blocking HTTP without the
 * engine caring. The stream is always closed by the engine, including on
 * cancellation, so adapters may hold a connection here.
 */
interface CloudDownload : Closeable {
    /** Total length of this response, or null when the provider does not report it. */
    val contentLength: Long?

    /** Revision observed when the stream opened; compared against the manifest (spec §20.6). */
    val revision: String?

    /** The byte range served, or null for the whole object. */
    val range: LongRange?

    /**
     * Reads up to [length] bytes into [destination] starting at [offset].
     * Returns the number of bytes read, or -1 at end of stream.
     */
    suspend fun read(destination: ByteArray, offset: Int, length: Int): Int
}

/** A destination folder prepared for writing (spec §5, §10). */
data class DestinationObject(
    val id: CloudObjectId,
    val relativePath: CloudPath,
    /** True when this call created the folder, false when it already existed. */
    val created: Boolean,
)

/**
 * Everything a provider needs to start an upload.
 *
 * [expectedHash] is the destination-native hash computed locally while the
 * bytes streamed through the device (spec §19.4). Providers that accept a hash
 * at upload time may send it; providers that return one at finish time are
 * verified against it (spec §21).
 */
data class UploadRequest(
    val account: AccountId,
    val parent: CloudObjectId,
    val name: String,
    /** Null when the size is not known in advance, e.g. an exported document (spec §20.1). */
    val size: Long?,
    val mimeType: String?,
    /** Honoured only when [ProviderCapabilities.supportsModifiedTimeWrite] is true. */
    val modifiedAt: Instant? = null,
    val expectedHash: ProviderHash? = null,
)

/**
 * A resumable upload in progress.
 *
 * [providerMetadata] and [expiresAt] are persisted verbatim (spec §12.2) so an
 * upload survives process death, and an expired session is abandoned rather
 * than resumed (spec §22.5).
 */
data class UploadSession(
    val id: String,
    val request: UploadRequest,
    val providerMetadata: String? = null,
    val expiresAt: Instant? = null,
) {
    fun isExpiredAt(now: Instant): Boolean = expiresAt?.isBefore(now) == true
}

/**
 * One byte range handed to the destination.
 *
 * Its bytes are either an array the caller already holds or a file on disk —
 * the engine's cached chunk (§14). The engine always uses the file. An adapter
 * must send the range by streaming [openStream], never by reading it whole:
 * an 8 MiB chunk read into an array and then copied into the HTTP library's
 * own buffers costs two chunks of heap, and that was half of the v0.6.1
 * `OutOfMemoryError`.
 *
 * Callers must not assume the bytes are in memory, or that [openStream] can be
 * called only once: a retry sends the same chunk again.
 *
 * Not a data class: structural equality over multi-megabyte content would be
 * a footgun.
 */
class Chunk private constructor(
    val offset: Long,
    val length: Int,
    /** True when this chunk completes the object. */
    val isFinal: Boolean,
    private val array: ByteArray?,
    private val file: Path?,
) {
    constructor(
        offset: Long,
        bytes: ByteArray,
        length: Int = bytes.size,
        isFinal: Boolean = false,
    ) : this(offset, length, isFinal, bytes, null) {
        require(length in 0..bytes.size) { "Chunk length $length is outside the buffer" }
    }

    init {
        require(offset >= 0) { "Chunk offset must not be negative" }
        require(length >= 0) { "Chunk length must not be negative" }
    }

    val endExclusive: Long get() = offset + length

    /** A fresh stream over exactly [length] bytes, each time it is called. */
    fun openStream(): InputStream = when {
        array != null -> ByteArrayInputStream(array, 0, length)
        else -> BoundedInputStream(Files.newInputStream(file!!), length.toLong())
    }

    /**
     * The whole range in memory. For an in-memory provider such as the fake,
     * which has to keep the bytes anyway; a network adapter streams instead.
     */
    fun readBytes(): ByteArray = openStream().use { stream ->
        // Not readNBytes: that is API 33 on Android, and this module ships to 26.
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = stream.read(out, filled, length - filled)
            check(read >= 0) { "chunk content ended at $filled of $length bytes" }
            filled += read
        }
        out
    }

    companion object {
        /** The first [length] bytes of [file], which must hold at least that many. */
        fun fromFile(offset: Long, file: Path, length: Int, isFinal: Boolean = false): Chunk =
            Chunk(offset, length, isFinal, null, file)
    }
}

/** Never reads past [limit], so a file longer than its chunk sends only the chunk. */
private class BoundedInputStream(private val inner: InputStream, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        return inner.read().also { if (it >= 0) limit-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val read = inner.read(b, off, minOf(len.toLong(), limit).toInt())
        if (read > 0) limit -= read
        return read
    }

    override fun close() = inner.close()
}

/**
 * How much of an upload the destination has acknowledged.
 *
 * This is the authority for deleting cached chunks: a chunk may be dropped only
 * once it falls below [acknowledgedBytes] (spec §14, §32.4).
 */
data class UploadProgress(
    val acknowledgedBytes: Long,
    val complete: Boolean = false,
) {
    init {
        require(acknowledgedBytes >= 0) { "acknowledgedBytes must not be negative" }
    }
}
