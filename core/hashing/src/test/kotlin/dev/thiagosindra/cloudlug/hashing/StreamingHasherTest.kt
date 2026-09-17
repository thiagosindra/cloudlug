package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingHasherTest {

    @Test
    fun `sha256 matches known vectors`() {
        KnownVectors.ALL.forEach { vector ->
            val hasher = Hashers.create(HashAlgorithm.SHA256)
            hasher.update(vector.bytes)
            assertEquals(vector.sha256, hasher.digest().value, "SHA-256 of $vector")
        }
    }

    @Test
    fun `md5 matches known vectors`() {
        KnownVectors.ALL.forEach { vector ->
            val hasher = Hashers.create(HashAlgorithm.MD5)
            hasher.update(vector.bytes)
            assertEquals(vector.md5, hasher.digest().value, "MD5 of $vector")
        }
    }

    @Test
    fun `dropbox content hash matches known vectors`() {
        KnownVectors.ALL.forEach { vector ->
            val hasher = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
            hasher.update(vector.bytes)
            assertEquals(vector.contentHash, hasher.digest().value, "content_hash of $vector")
        }
    }

    @Test
    fun `hashes are independent of how bytes are chunked`() {
        // The engine feeds whatever the network hands it; block boundaries must
        // come from the algorithm, not from arrival sizes (spec §19.4).
        val vector = KnownVectors.NINE_MIB
        listOf(1, 7, 4095, 64 * 1024, KnownVectors.BLOCK - 1, KnownVectors.BLOCK + 13).forEach { size ->
            val hasher = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
            var offset = 0
            while (offset < vector.bytes.size) {
                val length = minOf(size, vector.bytes.size - offset)
                hasher.update(vector.bytes, offset, length)
                offset += length
            }
            assertEquals(vector.contentHash, hasher.digest().value, "content_hash fed in ${size}-byte writes")
        }
    }

    @Test
    fun `zero length writes do not disturb the digest`() {
        val hasher = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
        hasher.update(ByteArray(0))
        hasher.update(KnownVectors.ABC.bytes)
        hasher.update(ByteArray(8), offset = 4, length = 0)
        assertEquals(KnownVectors.ABC.contentHash, hasher.digest().value)
    }

    @Test
    fun `bytesHashed tracks the stream length`() {
        val hasher = Hashers.create(HashAlgorithm.DROPBOX_CONTENT_HASH)
        hasher.update(KnownVectors.BLOCK_PLUS_ONE.bytes)
        assertEquals((KnownVectors.BLOCK + 1).toLong(), hasher.bytesHashed)
    }

    @Test
    fun `updating a finished hasher fails loudly`() {
        val hasher = Hashers.create(HashAlgorithm.SHA256)
        hasher.update(KnownVectors.ABC.bytes)
        hasher.digest()
        assertFailsWith<IllegalStateException> { hasher.update(KnownVectors.ABC.bytes) }
    }

    @Test
    fun `every declared algorithm has a hasher`() {
        HashAlgorithm.entries.forEach { algorithm ->
            assertEquals(algorithm, Hashers.create(algorithm).algorithm)
        }
    }
}
