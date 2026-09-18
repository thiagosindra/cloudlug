package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.io.Closeable
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
 * Not a data class: [bytes] is a mutable array, and structural equality over
 * multi-megabyte buffers would be a footgun.
 */
class Chunk(
    val offset: Long,
    val bytes: ByteArray,
    val length: Int = bytes.size,
    /** True when this chunk completes the object. */
    val isFinal: Boolean = false,
) {
    init {
        require(offset >= 0) { "Chunk offset must not be negative" }
        require(length in 0..bytes.size) { "Chunk length $length is outside the buffer" }
    }

    val endExclusive: Long get() = offset + length
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
