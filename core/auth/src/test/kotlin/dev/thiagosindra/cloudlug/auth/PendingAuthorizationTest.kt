package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.provider.dropbox.DropboxOAuth
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of §8.1 that has to survive being killed.
 *
 * A Custom Tab backgrounds CloudLug while the user signs in, which on a busy
 * phone is long enough to be killed. A verifier kept in a field would be gone,
 * and the user would come back to a redirect that cannot be exchanged.
 */
class PendingAuthorizationTest {

    private val secrets = FakeSecretStore()
    private val pending = PendingAuthorization(secrets)

    @Test
    fun `an attempt survives the round trip`() {
        val challenge = DropboxOAuth.newChallenge()
        pending.remember(challenge)

        // A different instance, because the old one's process is gone.
        assertEquals(challenge, PendingAuthorization(secrets).take())
    }

    @Test
    fun `taking an attempt consumes it`() {
        // A verifier is good for one exchange. Leaving a spent one behind is a
        // credential kept past its purpose (§8.3).
        pending.remember(DropboxOAuth.newChallenge())

        assertTrue(pending.take() != null)
        assertNull(pending.take())
        assertTrue(secrets.entries.isEmpty(), "the spent verifier is still stored: ${secrets.entries.keys}")
    }

    @Test
    fun `no attempt in progress is null, not a crash`() {
        assertNull(pending.take())
    }

    @Test
    fun `a corrupt attempt is no attempt`() {
        // §8.3 treats an unreadable credential as absent, and half a PKCE
        // challenge is worse than none: it would produce an exchange that fails
        // at Dropbox for reasons nobody could read off the device.
        secrets.put("dropbox.pending-authorization", "only-one-field")

        assertNull(pending.take())
    }

    @Test
    fun `a second attempt replaces the first`() {
        // Abandoning a sign-in and starting another is ordinary. Two live
        // verifiers would mean the second redirect could be exchanged against
        // the first attempt's state, which §8.4 exists to prevent.
        pending.remember(DropboxOAuth.newChallenge())
        val second = DropboxOAuth.newChallenge()
        pending.remember(second)

        assertEquals(second, pending.take())
    }

    @Test
    fun `discarding leaves nothing`() {
        pending.remember(DropboxOAuth.newChallenge())
        pending.discard()

        assertTrue(secrets.entries.isEmpty())
    }
}
