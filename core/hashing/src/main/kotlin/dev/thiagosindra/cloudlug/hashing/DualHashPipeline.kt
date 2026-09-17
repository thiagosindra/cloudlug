package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderHash

/**
 * The result of one pass over an object's bytes (spec §19.4).
 *
 * [sha256] is provider-independent and is what the idempotency record stores
 * (spec §19.2). [destinationNative] is the destination provider's own hash, so
 * verification is a metadata comparison instead of a re-download (spec §21); it
 * is null when the destination declares no server hash.
 */
data class DualHash(
    val sha256: ProviderHash,
    val destinationNative: ProviderHash?,
    val bytesHashed: Long,
)

/**
 * Computes SHA-256 and the destination's native hash in a single pass while
 * bytes move through the device (spec §19.4).
 *
 * The pipeline is fed once, in order, by the download producer; nothing
 * re-reads a cached object merely to hash it. When the destination's native
 * algorithm *is* SHA-256 the second hasher is elided rather than computing the
 * same digest twice.
 *
 * State is in-memory only. An item whose upload resumes in a later process
 * (spec §22.5) has no partial hash to restore: the engine either re-feeds the
 * cached chunks it still holds, or reports [DualHash.destinationNative] as
 * unavailable and verification falls back down the §21 ordering. See
 * docs/decisions.md ADR-0007.
 */
class DualHashPipeline(
    destinationAlgorithm: HashAlgorithm?,
) {
    private val sha256 = Hashers.create(HashAlgorithm.SHA256)
    private val native: StreamingHasher? = when (destinationAlgorithm) {
        null, HashAlgorithm.SHA256 -> null
        else -> Hashers.create(destinationAlgorithm)
    }
    private val nativeIsSha256 = destinationAlgorithm == HashAlgorithm.SHA256

    var bytesHashed: Long = 0
        private set

    private var finished = false

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        check(!finished) { "Hash pipeline already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "update($offset, $length) is outside a ${bytes.size}-byte buffer"
        }
        sha256.update(bytes, offset, length)
        native?.update(bytes, offset, length)
        bytesHashed += length
    }

    fun finish(): DualHash {
        check(!finished) { "Hash pipeline already finished" }
        finished = true
        val whole = sha256.digest()
        return DualHash(
            sha256 = whole,
            destinationNative = if (nativeIsSha256) whole else native?.digest(),
            bytesHashed = bytesHashed,
        )
    }
}
