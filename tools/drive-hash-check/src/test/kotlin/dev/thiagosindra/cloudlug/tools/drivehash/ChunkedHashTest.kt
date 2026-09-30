package dev.thiagosindra.cloudlug.tools.drivehash

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks this tool's own plumbing, not the algorithms.
 *
 * The reference is the JDK's `MessageDigest`, which shares no code with
 * `:core:hashing` — the thing under suspicion. If a MISMATCH is reported and
 * this passes, the fault is in the hashing or in a misreading of Drive; if
 * this fails, the tool is feeding its hasher wrongly and the run means nothing.
 */
class ChunkedHashTest {

    private fun jdk(algorithm: String, bytes: ByteArray) =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunked(bytes: ByteArray): Pair<String, String> {
        val hash = ChunkedHash()
        for (start in bytes.indices step CHUNK) {
            val chunk = bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size))
            hash.feed(chunk, chunk.size)
        }
        val result = hash.finish()
        return result.destinationNative!!.value to result.sha256.value
    }

    @Test
    fun `one byte agrees with the JDK`() {
        val bytes = ByteArray(1) { 0x2a }
        assertEquals(jdk("MD5", bytes) to jdk("SHA-256", bytes), chunked(bytes))
    }

    @Test
    fun `a restore across a chunk boundary agrees with the JDK`() {
        val bytes = ByteArray(CHUNK + 1) { (it % 251).toByte() }
        assertEquals(jdk("MD5", bytes) to jdk("SHA-256", bytes), chunked(bytes))
    }

    @Test
    fun `a case passes only when md5 was actually reported`() {
        val md5Missing = Outcome(listOf(Field("md5Checksum in the finishing response", "a", null)), emptyList())
        assertFalse(md5Missing.passed, "nothing reported must not read as everything matching")

        val md5Matched = Outcome(
            listOf(
                Field("md5Checksum in the finishing response", "ab", "AB"),
                Field("sha256Checksum in the finishing response", "cd", null),
            ),
            emptyList(),
        )
        assertTrue(md5Matched.passed)
        assertFalse(md5Matched.copy(protocolProblems = listOf("308 missing")).passed)
    }
}
