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
 * Computes one hash incrementally as bytes stream through the device
 * (spec §19.4).
 *
 * A hasher is fed every byte of the object exactly once, in order, and is not
 * reusable after [digest]. Implementations are not thread-safe; the pipeline
 * owns one per item.
 *
 * Every hasher is **checkpointable**: [checkpoint] snapshots its internal state
 * so hashing can resume in a later process, which is what lets verification
 * survive process death without re-reading acknowledged chunks that the cache
 * has already deleted (spec §15.3, §19.4, §21).
 */
interface StreamingHasher {
    val algorithm: HashAlgorithm

    /** Number of bytes fed so far. */
    val bytesHashed: Long

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Finishes the computation. Calling [update] afterwards is a programming error. */
    fun digest(): ProviderHash

    /**
     * Snapshots state as of the bytes fed so far. Restoring it with
     * [Hashers.restore] yields a hasher that continues identically; taking a
     * checkpoint does not disturb this hasher.
     */
    fun checkpoint(): HashCheckpoint
}

internal const val KIND_MERKLE_DAMGARD = 1
internal const val KIND_BLOCK_LIST = 2
private const val CHECKPOINT_VERSION = 1

/**
 * Frames a hasher's state with a version and its algorithm, then Base64-encodes
 * it so the whole thing is one text column (spec §12.2 `hashCheckpoint`).
 *
 * The algorithm travels inside the payload as well as being supplied by the
 * caller on restore, so a checkpoint written for one algorithm can never be
 * silently fed to another after, say, a destination provider change.
 */
internal fun encodeCheckpoint(
    algorithm: HashAlgorithm,
    body: (DataOutputStream) -> Unit,
): HashCheckpoint {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeByte(CHECKPOINT_VERSION)
        out.writeUTF(algorithm.id)
        body(out)
    }
    return HashCheckpoint(Base64.getEncoder().encodeToString(bytes.toByteArray()))
}

/** Unwraps a checkpoint, verifying version and algorithm before handing over the body. */
internal fun <T> decodeCheckpoint(
    algorithm: HashAlgorithm,
    checkpoint: HashCheckpoint,
    body: (DataInputStream) -> T,
): T {
    val raw = try {
        Base64.getDecoder().decode(checkpoint.encoded)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Hash checkpoint is not valid Base64", e)
    }
    return DataInputStream(ByteArrayInputStream(raw)).use { input ->
        val version = input.readUnsignedByte()
        require(version == CHECKPOINT_VERSION) { "Unsupported hash checkpoint version $version" }
        val id = input.readUTF()
        require(id == algorithm.id) { "Checkpoint is for $id, not ${algorithm.id}" }
        body(input)
    }
}

/**
 * SHA-256 over the concatenated SHA-256 digests of fixed-size blocks — the
 * algorithm Dropbox publishes as `content_hash` (spec §19.4).
 *
 * The block size is a constructor parameter rather than a constant because it
 * is a property of the algorithm, not of CloudLug: a future provider using the
 * same construction with a different block size needs no new class. Bytes
 * arrive in arbitrarily sized writes, so this buffers up to one block.
 *
 * Checkpointing does **not** store the list of completed block digests that
 * spec §19.4 describes. The outer SHA-256 is itself a streaming hasher, so
 * folding each block digest into it as soon as the block closes yields the same
 * result from constant state instead of 32 bytes per 4 MiB — see
 * docs/decisions.md ADR-0019.
 */
internal class BlockListSha256Hasher(
    override val algorithm: HashAlgorithm = HashAlgorithm.DROPBOX_CONTENT_HASH,
    private val blockSize: Int = DROPBOX_BLOCK_SIZE,
) : StreamingHasher {

    private var outer = Sha256Hasher()
    private var block = Sha256Hasher()
    private var bytesInBlock = 0
    private var finished = false

    override var bytesHashed: Long = 0
        private set

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "$algorithm hasher already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "update($offset, $length) is outside a ${bytes.size}-byte buffer"
        }
        var consumed = 0
        while (consumed < length) {
            val room = blockSize - bytesInBlock
            val take = minOf(room, length - consumed)
            block.update(bytes, offset + consumed, take)
            bytesInBlock += take
            consumed += take
            if (bytesInBlock == blockSize) closeBlock()
        }
        bytesHashed += length
    }

    override fun digest(): ProviderHash {
        check(!finished) { "$algorithm hasher already finished" }
        // A trailing partial block still contributes a digest; an empty object
        // contributes none, so its content hash is SHA-256 of the empty string.
        if (bytesInBlock > 0) closeBlock()
        finished = true
        return ProviderHash(algorithm, outer.digest().value)
    }

    private fun closeBlock() {
        outer.update(hexToBytes(block.digest().value))
        block = Sha256Hasher()
        bytesInBlock = 0
    }

    override fun checkpoint(): HashCheckpoint = encodeCheckpoint(algorithm) { out ->
        out.writeByte(KIND_BLOCK_LIST)
        out.writeLong(bytesHashed)
        out.writeInt(blockSize)
        out.writeInt(bytesInBlock)
        outer.encodeTo(out)
        block.encodeTo(out)
    }

    internal fun restoreFrom(input: DataInputStream) {
        bytesHashed = input.readLong()
        val storedBlockSize = input.readInt()
        require(storedBlockSize == blockSize) {
            "Checkpoint uses a $storedBlockSize-byte block, this hasher uses $blockSize"
        }
        bytesInBlock = input.readInt()
        outer = Sha256Hasher().apply { decodeFrom(input) }
        block = Sha256Hasher().apply { decodeFrom(input) }
    }

    companion object {
        /** Dropbox `content_hash` block size: 4 MiB. */
        const val DROPBOX_BLOCK_SIZE: Int = 4 * 1024 * 1024

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Builds the hasher for an algorithm a provider declared through
 * [dev.thiagosindra.cloudlug.provider.ProviderCapabilities.nativeHashAlgorithm].
 *
 * Adding a provider whose hash is one of these needs no code here; a genuinely
 * new algorithm adds one [HashAlgorithm] constant and one branch.
 */
object Hashers {
    fun create(algorithm: HashAlgorithm): StreamingHasher = when (algorithm) {
        HashAlgorithm.SHA256 -> Sha256Hasher()
        HashAlgorithm.SHA1 -> Sha1Hasher()
        HashAlgorithm.MD5 -> Md5Hasher()
        HashAlgorithm.DROPBOX_CONTENT_HASH -> BlockListSha256Hasher()
    }

    /**
     * Rebuilds a hasher from a [checkpoint] taken by [StreamingHasher.checkpoint].
     *
     * Throws [IllegalArgumentException] if the payload is malformed or was
     * written for a different algorithm; a caller that cannot restore must
     * treat the item as needing a re-hash rather than completing it unverified
     * (spec §21).
     */
    fun restore(algorithm: HashAlgorithm, checkpoint: HashCheckpoint): StreamingHasher =
        decodeCheckpoint(algorithm, checkpoint) { input ->
            when (val kind = input.readUnsignedByte()) {
                KIND_MERKLE_DAMGARD -> when (algorithm) {
                    HashAlgorithm.SHA256 -> Sha256Hasher().apply { decodeFrom(input) }
                    HashAlgorithm.SHA1 -> Sha1Hasher().apply { decodeFrom(input) }
                    HashAlgorithm.MD5 -> Md5Hasher().apply { decodeFrom(input) }
                    HashAlgorithm.DROPBOX_CONTENT_HASH ->
                        throw IllegalArgumentException("$algorithm is not a Merkle-Damgard hasher")
                }

                KIND_BLOCK_LIST -> BlockListSha256Hasher().apply { restoreFrom(input) }
                else -> throw IllegalArgumentException("Unknown hash checkpoint kind $kind")
            }
        }
}
