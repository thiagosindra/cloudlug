package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.security.MessageDigest

/**
 * Computes one hash incrementally as bytes stream through the device
 * (spec §19.4).
 *
 * A hasher is fed every byte of the object exactly once, in order, and is not
 * reusable after [digest]. Implementations are not thread-safe; the pipeline
 * owns one per item.
 */
interface StreamingHasher {
    val algorithm: HashAlgorithm

    /** Number of bytes fed so far. */
    val bytesHashed: Long

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Finishes the computation. Calling [update] afterwards is a programming error. */
    fun digest(): ProviderHash
}

/** A hasher backed by a JDK [MessageDigest]: SHA-256, SHA-1 or MD5. */
internal class MessageDigestHasher(
    override val algorithm: HashAlgorithm,
    jdkName: String,
) : StreamingHasher {

    private val digest = MessageDigest.getInstance(jdkName)
    private var finished = false
    override var bytesHashed: Long = 0
        private set

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "$algorithm hasher already finished" }
        digest.update(bytes, offset, length)
        bytesHashed += length
    }

    override fun digest(): ProviderHash {
        finished = true
        return ProviderHash.of(algorithm, digest.digest())
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
 */
internal class BlockListSha256Hasher(
    override val algorithm: HashAlgorithm = HashAlgorithm.DROPBOX_CONTENT_HASH,
    private val blockSize: Int = DROPBOX_BLOCK_SIZE,
) : StreamingHasher {

    private val outer = MessageDigest.getInstance("SHA-256")
    private var block = MessageDigest.getInstance("SHA-256")
    private var bytesInBlock = 0
    private var finished = false
    override var bytesHashed: Long = 0
        private set

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "$algorithm hasher already finished" }
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
        // A trailing partial block still contributes a digest; an empty object
        // contributes none, so its content hash is SHA-256 of the empty string.
        if (bytesInBlock > 0) closeBlock()
        finished = true
        return ProviderHash.of(algorithm, outer.digest())
    }

    private fun closeBlock() {
        outer.update(block.digest())
        block = MessageDigest.getInstance("SHA-256")
        bytesInBlock = 0
    }

    companion object {
        /** Dropbox `content_hash` block size: 4 MiB. */
        const val DROPBOX_BLOCK_SIZE: Int = 4 * 1024 * 1024
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
        HashAlgorithm.SHA256 -> MessageDigestHasher(algorithm, "SHA-256")
        HashAlgorithm.SHA1 -> MessageDigestHasher(algorithm, "SHA-1")
        HashAlgorithm.MD5 -> MessageDigestHasher(algorithm, "MD5")
        HashAlgorithm.DROPBOX_CONTENT_HASH -> BlockListSha256Hasher()
    }
}
