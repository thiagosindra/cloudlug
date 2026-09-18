package dev.thiagosindra.cloudlug.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderHashTest {

    @Test
    fun `hex encoding is lower case and zero padded`() {
        val hash = ProviderHash.of(HashAlgorithm.MD5, byteArrayOf(0x00, 0x0f, 0xff.toByte()))
        assertEquals("000fff", hash.value)
    }

    @Test
    fun `non hex values are rejected`() {
        assertFailsWith<IllegalArgumentException> { ProviderHash(HashAlgorithm.SHA256, "ABCD") }
        assertFailsWith<IllegalArgumentException> { ProviderHash(HashAlgorithm.SHA256, "zz") }
    }

    @Test
    fun `hashes of different algorithms are never comparable`() {
        val md5 = ProviderHash(HashAlgorithm.MD5, "abcd")
        val sha = ProviderHash(HashAlgorithm.SHA256, "abcd")
        assertFalse(md5.comparableTo(sha))
        assertFalse(md5.matches(sha))
    }

    @Test
    fun `same algorithm and digest matches`() {
        val a = ProviderHash(HashAlgorithm.DROPBOX_CONTENT_HASH, "beef")
        val b = ProviderHash(HashAlgorithm.DROPBOX_CONTENT_HASH, "beef")
        assertTrue(a.matches(b))
    }

    @Test
    fun `algorithm ids round trip`() {
        HashAlgorithm.entries.forEach { assertEquals(it, HashAlgorithm.fromId(it.id)) }
    }
}
