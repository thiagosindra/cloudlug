package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

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
 * The pipeline is **checkpointable**. [checkpoint] snapshots both hashers, and
 * the engine persists the result as the item's `hashCheckpoint` in the same
 * transaction as each chunk acknowledgment (spec §15.3). After process death
 * [restore] resumes from exactly the acknowledged byte offset, so hashing never
 * needs chunks the cache has already deleted and verification never degrades
 * (spec §21). This replaces the v0.1 behaviour recorded in ADR-0007.
 */
class DualHashPipeline private constructor(
    private val sha256: StreamingHasher,
    private val native: StreamingHasher?,
    private val nativeIsSha256: Boolean,
    bytesHashed: Long,
) {
    constructor(destinationAlgorithm: HashAlgorithm?) : this(
        sha256 = Hashers.create(HashAlgorithm.SHA256),
        native = when (destinationAlgorithm) {
            null, HashAlgorithm.SHA256 -> null
            else -> Hashers.create(destinationAlgorithm)
        },
        nativeIsSha256 = destinationAlgorithm == HashAlgorithm.SHA256,
        bytesHashed = 0,
    )

    var bytesHashed: Long = bytesHashed
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

    /**
     * Snapshots both hashers as of the bytes fed so far. Taking a checkpoint
     * does not disturb the pipeline, so the engine can checkpoint on every
     * chunk acknowledgment and keep streaming.
     */
    fun checkpoint(): HashCheckpoint {
        check(!finished) { "Hash pipeline already finished" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(PIPELINE_VERSION)
            out.writeLong(bytesHashed)
            out.writeUTF(sha256.checkpoint().encoded)
            out.writeBoolean(native != null)
            native?.let { out.writeUTF(it.checkpoint().encoded) }
        }
        return HashCheckpoint(Base64.getEncoder().encodeToString(bytes.toByteArray()))
    }

    companion object {
        private const val PIPELINE_VERSION = 1

        /**
         * Rebuilds a pipeline from a [checkpoint], for the same
         * [destinationAlgorithm] that produced it.
         *
         * Throws [IllegalArgumentException] if the payload is malformed or was
         * written for a different destination algorithm. A caller that cannot
         * restore must re-feed the object's bytes rather than complete the item
         * unverified (spec §21).
         */
        fun restore(
            destinationAlgorithm: HashAlgorithm?,
            checkpoint: HashCheckpoint,
        ): DualHashPipeline {
            val raw = try {
                Base64.getDecoder().decode(checkpoint.encoded)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Hash pipeline checkpoint is not valid Base64", e)
            }
            return DataInputStream(ByteArrayInputStream(raw)).use { input ->
                val version = input.readUnsignedByte()
                require(version == PIPELINE_VERSION) {
                    "Unsupported hash pipeline checkpoint version $version"
                }
                val bytesHashed = input.readLong()
                val sha256 = Hashers.restore(
                    HashAlgorithm.SHA256,
                    HashCheckpoint(input.readUTF()),
                )
                val hasNative = input.readBoolean()
                val nativeExpected = destinationAlgorithm != null &&
                    destinationAlgorithm != HashAlgorithm.SHA256
                require(hasNative == nativeExpected) {
                    "Checkpoint ${if (hasNative) "has" else "has no"} native hasher, but " +
                        "$destinationAlgorithm ${if (nativeExpected) "needs" else "does not need"} one"
                }
                val native = if (hasNative) {
                    Hashers.restore(destinationAlgorithm!!, HashCheckpoint(input.readUTF()))
                } else {
                    null
                }
                DualHashPipeline(
                    sha256 = sha256,
                    native = native,
                    nativeIsSha256 = destinationAlgorithm == HashAlgorithm.SHA256,
                    bytesHashed = bytesHashed,
                )
            }
        }
    }
}
