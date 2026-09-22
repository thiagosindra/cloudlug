package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.model.AccountId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One credential per account (§8.3), and the upgrade path into that.
 *
 * The Keystore half is asserted on a device in `KeystoreSecretStoreTest`; what
 * is here is the keying, which is where a Dropbox-to-Dropbox transfer would go
 * wrong.
 */
class KeystoreRefreshTokensTest {

    private val secrets = FakeSecretStore()
    private val tokens = KeystoreRefreshTokens(secrets)

    @Test
    fun `each account keeps its own credential`() {
        tokens.write(SOURCE, "source-refresh-token")
        tokens.write(DESTINATION, "destination-refresh-token")

        assertEquals("source-refresh-token", tokens.read(SOURCE))
        assertEquals("destination-refresh-token", tokens.read(DESTINATION))
    }

    @Test
    fun `disconnecting one account leaves the other connected`() {
        tokens.write(SOURCE, "source-refresh-token")
        tokens.write(DESTINATION, "destination-refresh-token")

        tokens.clear(SOURCE)

        assertNull(tokens.read(SOURCE))
        assertEquals("destination-refresh-token", tokens.read(DESTINATION))
    }

    @Test
    fun `an account nobody connected has no credential`() {
        assertNull(tokens.read(SOURCE))
    }

    @Test
    fun `a credential written before keys carried an account is adopted`() {
        // What v0.3 wrote. Without this the upgrade is broken in the worst way:
        // the account row survives in Room, so §24.5 still says connected,
        // while the credential sits under a key nothing reads any more.
        secrets.put("dropbox.refresh-token", "the-v0.3-refresh-token")

        assertEquals("the-v0.3-refresh-token", tokens.read(SOURCE))
    }

    @Test
    fun `adopting the legacy credential happens once`() {
        secrets.put("dropbox.refresh-token", "the-v0.3-refresh-token")
        tokens.read(SOURCE)

        // Moved, not copied: leaving it would be a live credential under a key
        // no account owns (§8.3).
        assertNull(secrets.get("dropbox.refresh-token"))
        assertEquals("the-v0.3-refresh-token", tokens.read(SOURCE))
        assertNull(
            tokens.read(DESTINATION),
            "the legacy credential was adopted a second time, by an account it does not belong to",
        )
    }

    @Test
    fun `disconnecting removes the legacy credential too`() {
        // Otherwise a disconnect leaves the pre-upgrade copy behind, and the
        // next account to connect would adopt somebody else's credential.
        secrets.put("dropbox.refresh-token", "the-v0.3-refresh-token")
        tokens.write(SOURCE, "current")

        tokens.clear(SOURCE)

        assertTrue(secrets.entries.isEmpty(), "something survived the disconnect: ${secrets.entries.keys}")
    }

    private companion object {
        val SOURCE = AccountId("dbid:AAA")
        val DESTINATION = AccountId("dbid:BBB")
    }
}
