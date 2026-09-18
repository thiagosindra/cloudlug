package dev.thiagosindra.cloudlug.tools.dropbox

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks this tool's own plumbing, not the algorithm.
 *
 * The point of the tool is to decide whether `core:hashing` agrees with
 * Dropbox. That verdict is only worth anything if the tool itself feeds the
 * hasher correctly, so these two vectors were computed independently — plain
 * SHA-256 of the concatenated SHA-256 digests of each 4 MiB block — rather than
 * taken from `core:hashing`, which is the thing under suspicion.
 *
 * If the user reports a MISMATCH and this test passes, the fault is in the
 * hasher or in a shared misreading of the spec, which is exactly the question
 * §36 asks. If this test fails, the tool is lying and the run means nothing.
 */
class LocalHashWiringTest {

    @Test
    fun `a single byte hashes as one short block`() {
        assertEquals(ONE_BYTE, localHash(ByteArray(1) { 0x2a }))
    }

    @Test
    fun `one byte past the block boundary starts a second block`() {
        val spanning = ByteArray(BLOCK + 1) { (it % 251).toByte() }
        assertEquals(BLOCK_PLUS_ONE, localHash(spanning))
    }

    private companion object {
        const val BLOCK = 4 * 1024 * 1024
        const val ONE_BYTE = "ff122c0ea37f12c5c0f330b2616791df8cb8cc8f1114304afbf0cff5d79cec54"
        const val BLOCK_PLUS_ONE = "4a6cc0a344febaa07772e7c974834b2fb1d24594d4ba15f27c97a54699709f44"
    }
}
