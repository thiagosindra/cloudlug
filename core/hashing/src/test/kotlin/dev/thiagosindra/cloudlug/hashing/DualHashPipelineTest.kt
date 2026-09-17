package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DualHashPipelineTest {

    @Test
    fun `one pass yields sha256 and the destination native hash`() {
        val vector = KnownVectors.NINE_MIB
        val pipeline = DualHashPipeline(HashAlgorithm.DROPBOX_CONTENT_HASH)
        pipeline.update(vector.bytes)
        val result = pipeline.finish()

        assertEquals(vector.sha256, result.sha256.value)
        assertEquals(vector.contentHash, result.destinationNative?.value)
        assertEquals(HashAlgorithm.DROPBOX_CONTENT_HASH, result.destinationNative?.algorithm)
        assertEquals(vector.bytes.size.toLong(), result.bytesHashed)
    }

    @Test
    fun `md5 destination is computed alongside sha256`() {
        val vector = KnownVectors.BLOCK_PLUS_ONE
        val pipeline = DualHashPipeline(HashAlgorithm.MD5)
        // Feed in pieces, the way chunks arrive from the network.
        var offset = 0
        while (offset < vector.bytes.size) {
            val length = minOf(1_000_003, vector.bytes.size - offset)
            pipeline.update(vector.bytes, offset, length)
            offset += length
        }
        val result = pipeline.finish()

        assertEquals(vector.sha256, result.sha256.value)
        assertEquals(vector.md5, result.destinationNative?.value)
    }

    @Test
    fun `a sha256 destination reuses the single digest rather than hashing twice`() {
        val pipeline = DualHashPipeline(HashAlgorithm.SHA256)
        pipeline.update(KnownVectors.ABC.bytes)
        val result = pipeline.finish()

        assertEquals(KnownVectors.ABC.sha256, result.sha256.value)
        assertSame(result.sha256, result.destinationNative)
    }

    @Test
    fun `a destination without a server hash still yields sha256`() {
        val pipeline = DualHashPipeline(destinationAlgorithm = null)
        pipeline.update(KnownVectors.ABC.bytes)
        val result = pipeline.finish()

        assertEquals(KnownVectors.ABC.sha256, result.sha256.value)
        assertNull(result.destinationNative)
    }

    @Test
    fun `an empty object hashes without any update call`() {
        val result = DualHashPipeline(HashAlgorithm.DROPBOX_CONTENT_HASH).finish()

        assertEquals(KnownVectors.EMPTY.sha256, result.sha256.value)
        assertEquals(KnownVectors.EMPTY.contentHash, result.destinationNative?.value)
        assertEquals(0, result.bytesHashed)
    }

    @Test
    fun `using a finished pipeline fails loudly`() {
        val pipeline = DualHashPipeline(HashAlgorithm.MD5)
        pipeline.finish()
        assertFailsWith<IllegalStateException> { pipeline.update(KnownVectors.ABC.bytes) }
        assertFailsWith<IllegalStateException> { pipeline.finish() }
    }

    @Test
    fun `out of bounds writes are rejected`() {
        val pipeline = DualHashPipeline(HashAlgorithm.MD5)
        assertFailsWith<IllegalArgumentException> { pipeline.update(ByteArray(4), offset = 2, length = 3) }
        assertFailsWith<IllegalArgumentException> { pipeline.update(ByteArray(4), offset = -1, length = 1) }
    }
}
