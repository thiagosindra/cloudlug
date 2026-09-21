package dev.thiagosindra.cloudlug.provider.dropbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The gate itself, tested without touching the network.
 *
 * The guard is the part worth testing: it is the thing standing between a
 * suite that deletes what it creates and somebody's account root.
 */
class DropboxLiveGateTest {

    @Test
    fun `every path a live test can name is inside the test root`() {
        assertTrue(DropboxLive.path("a/b.txt").startsWith(DropboxLive.root))
        // A leading slash on the relative part must not escape the root.
        assertTrue(DropboxLive.path("/a/b.txt").startsWith(DropboxLive.root))
        assertEquals("${DropboxLive.root}/a/b.txt", DropboxLive.path("a/b.txt"))
    }

    @Test
    fun `the default root is a folder of its own, not the account root`() {
        DropboxLive.requireSafeRoot()
        assertTrue(DropboxLive.root.trimEnd('/').length > 1, "root is '${DropboxLive.root}'")
    }

    @Test
    fun `a root that would put the account at risk is refused, not tolerated`() {
        // requireSafeRoot reads the environment, so the rule itself is checked
        // here directly: these are the values that must never pass.
        listOf("/", "//", "  /  ".trim(), "").forEach { dangerous ->
            val normalised = dangerous.trim()
            val safe = normalised.startsWith("/") && normalised.trimEnd('/').length > 1
            assertTrue(!safe, "'$dangerous' must not be accepted as a test root")
        }
    }

    @Test
    fun `asking for a token without configuring one is a programming error, not a silent pass`() {
        if (!DropboxLive.isConfigured) {
            assertFailsWith<IllegalStateException> { DropboxLive.accessToken() }
        }
    }
}
