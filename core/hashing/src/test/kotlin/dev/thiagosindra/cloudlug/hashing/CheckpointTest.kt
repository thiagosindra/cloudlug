package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec §19.4: hashers carry serializable state so hashing resumes after process
 * death, and §21: verification therefore never degrades. See ADR-0007, which
 * v1.2 overrules, and ADR-0019 for the block-list encoding.
 */
class CheckpointTest {

    private fun jdk(name: String, bytes: ByteArray): String =
        MessageDigest.getInstance(name).digest(bytes).joinToString("") { "%02x".format(it) }

    // The hand-written hashers replaced MessageDigest, so the JDK is the
    // reference implementation they are checked against over random inputs —
    // the known vectors alone would not catch a bug in an untested length class.
    @Test
    fun `hand-written hashers agree with the JDK across every length class`() {
        val random = Random(20260918)
        // Lengths either side of the 64-byte block and the 56-byte padding
        // boundary, where Merkle-Damgard implementations go wrong.
        val lengths = listOf(0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 1000, 4096)
        for (length in lengths) {
            val bytes = random.nextBytes(length)
            for ((algorithm, jdkName) in listOf(
                HashAlgorithm.SHA256 to "SHA-256",
                HashAlgorithm.SHA1 to "SHA-1",
                HashAlgorithm.MD5 to "MD5",
            )) {
                val hasher = Hashers.create(algorithm)
                hasher.update(bytes)
                assertEquals(jdk(jdkName, bytes), hasher.digest().value, "$algorithm over $length bytes")
            }
        }
    }

    @Test
    fun `a checkpoint taken mid-stream resumes to the same digest`() {
        val random = Random(7)
        val bytes = random.nextBytes(5000)
        for (algorithm in HashAlgorithm.entries) {
            val direct = Hashers.create(algorithm).apply { update(bytes) }.digest()

            val first = Hashers.create(algorithm)
            first.update(bytes, 0, 1234)
            val resumed = Hashers.restore(algorithm, first.checkpoint())
            resumed.update(bytes, 1234, bytes.size - 1234)

            assertEquals(direct, resumed.digest(), "$algorithm resumed from a checkpoint")
        }
    }

    @Test
    fun `taking a checkpoint does not disturb the hasher`() {
        val bytes = Random(11).nextBytes(3000)
        for (algorithm in HashAlgorithm.entries) {
            val hasher = Hashers.create(algorithm)
            hasher.update(bytes, 0, 1500)
            repeat(3) { hasher.checkpoint() }
            hasher.update(bytes, 1500, 1500)
            val direct = Hashers.create(algorithm).apply { update(bytes) }.digest()
            assertEquals(direct, hasher.digest(), "$algorithm after interleaved checkpoints")
        }
    }

    /**
     * The §15.3 shape: checkpoint on every chunk acknowledgment, then lose the
     * process and carry on from the last checkpoint with the cache already
     * emptied of acknowledged chunks.
     */
    @Test
    fun `checkpointing on every chunk survives repeated process death`() {
        val bytes = Random(13).nextBytes(9 * 1024 * 1024 + 517)
        val chunk = 8 * 1024 * 1024 / 8
        for (algorithm in HashAlgorithm.entries) {
            var checkpoint: HashCheckpoint? = null
            var offset = 0
            while (offset < bytes.size) {
                // Each iteration is a fresh process: nothing survives but the checkpoint.
                val hasher = checkpoint?.let { Hashers.restore(algorithm, it) }
                    ?: Hashers.create(algorithm)
                val take = minOf(chunk, bytes.size - offset)
                hasher.update(bytes, offset, take)
                offset += take
                checkpoint = hasher.checkpoint()
            }
            val resumed = Hashers.restore(algorithm, checkpoint!!)
            val direct = Hashers.create(algorithm).apply { update(bytes) }.digest()
            assertEquals(direct, resumed.digest(), "$algorithm across ${bytes.size} bytes")
        }
    }

    @Test
    fun `block list checkpoints restore mid-block and on a block boundary`() {
        val block = BlockListSha256Hasher.DROPBOX_BLOCK_SIZE
        val bytes = Random(17).nextBytes(block * 2 + 1024)
        // Exactly on a boundary, and a byte either side of one.
        for (cut in listOf(block - 1, block, block + 1, block * 2)) {
            val first = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
            first.update(bytes, 0, cut)
            val resumed = Hashers.restore(HashAlgorithm.DROPBOX_CONTENT_HASH, first.checkpoint())
            resumed.update(bytes, cut, bytes.size - cut)

            val direct = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
                .apply { update(bytes) }.digest()
            assertEquals(direct, resumed.digest(), "content hash resumed at $cut")
        }
    }

    @Test
    fun `block list checkpoint stays small regardless of object size`() {
        // ADR-0019: the outer SHA-256 absorbs each block digest as the block
        // closes, so state does not grow at 32 bytes per 4 MiB.
        val block = BlockListSha256Hasher.DROPBOX_BLOCK_SIZE
        val hasher = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
        val filler = ByteArray(block)
        val short = hasher.checkpoint().encoded.length
        repeat(8) { hasher.update(filler) }
        val after = hasher.checkpoint().encoded.length
        assertEquals(short, after, "checkpoint grew after 32 MiB")
        assertTrue(after < 512, "checkpoint is $after characters")
    }

    @Test
    fun `bytesHashed survives a checkpoint`() {
        val bytes = Random(19).nextBytes(2048)
        for (algorithm in HashAlgorithm.entries) {
            val hasher = Hashers.create(algorithm)
            hasher.update(bytes, 0, 700)
            val resumed = Hashers.restore(algorithm, hasher.checkpoint())
            assertEquals(700L, resumed.bytesHashed, "$algorithm")
        }
    }

    @Test
    fun `a checkpoint cannot be restored as the wrong algorithm`() {
        val hasher = Hashers.create(HashAlgorithm.SHA256)
        hasher.update("abc".toByteArray())
        val checkpoint = hasher.checkpoint()
        assertFailsWith<IllegalArgumentException> {
            Hashers.restore(HashAlgorithm.MD5, checkpoint)
        }
    }

    @Test
    fun `a malformed checkpoint is rejected rather than silently mis-hashing`() {
        assertFailsWith<IllegalArgumentException> {
            Hashers.restore(HashAlgorithm.SHA256, HashCheckpoint("not base64 !!"))
        }
        assertFailsWith<IllegalArgumentException> {
            Hashers.restore(HashAlgorithm.SHA256, HashCheckpoint("AAAA"))
        }
    }

    @Test
    fun `checkpoints of different prefixes differ`() {
        val bytes = Random(23).nextBytes(512)
        val a = Hashers.create(HashAlgorithm.SHA256).apply { update(bytes, 0, 100) }.checkpoint()
        val b = Hashers.create(HashAlgorithm.SHA256).apply { update(bytes, 0, 200) }.checkpoint()
        assertNotEquals(a, b)
    }

    @Test
    fun `the dual hash pipeline resumes both hashers from one checkpoint`() {
        val bytes = Random(29).nextBytes(BlockListSha256Hasher.DROPBOX_BLOCK_SIZE + 4096)
        for (destination in listOf(
            null,
            HashAlgorithm.SHA256,
            HashAlgorithm.MD5,
            HashAlgorithm.DROPBOX_CONTENT_HASH,
        )) {
            val direct = DualHashPipeline(destination).apply { update(bytes) }.finish()

            val first = DualHashPipeline(destination)
            first.update(bytes, 0, 5000)
            val resumed = DualHashPipeline.restore(destination, first.checkpoint())
            resumed.update(bytes, 5000, bytes.size - 5000)
            val actual = resumed.finish()

            assertEquals(direct.sha256, actual.sha256, "sha256 for destination $destination")
            assertEquals(
                direct.destinationNative,
                actual.destinationNative,
                "native hash for destination $destination",
            )
            assertEquals(direct.bytesHashed, actual.bytesHashed, "bytes for destination $destination")
        }
    }

    @Test
    fun `a pipeline checkpoint cannot be restored for a different destination`() {
        val pipeline = DualHashPipeline(HashAlgorithm.MD5)
        pipeline.update("abc".toByteArray())
        val checkpoint = pipeline.checkpoint()
        // MD5 -> DROPBOX_CONTENT_HASH keeps a native hasher present but changes
        // its algorithm; MD5 -> null removes it entirely. Both must be caught.
        assertFailsWith<IllegalArgumentException> {
            DualHashPipeline.restore(HashAlgorithm.DROPBOX_CONTENT_HASH, checkpoint)
        }
        assertFailsWith<IllegalArgumentException> {
            DualHashPipeline.restore(null, checkpoint)
        }
    }

    @Test
    fun `checkpointing a finished pipeline fails loudly`() {
        val pipeline = DualHashPipeline(HashAlgorithm.MD5)
        pipeline.update("abc".toByteArray())
        pipeline.finish()
        assertFailsWith<IllegalStateException> { pipeline.checkpoint() }
    }
}
