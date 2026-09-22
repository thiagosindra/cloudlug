package dev.thiagosindra.cloudlug.auth

import android.content.Intent
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §24.5's accounts, and ADR-0028's rule about the order of a disconnect.
 */
class AccountRepositoryTest {

    private val database = InMemoryCloudLugDatabase()

    /** Records what it was asked to do, so the *order* can be asserted. */
    private class RecordingConnector(
        var failRevoke: Boolean = false,
    ) : AccountConnector {
        val calls = mutableListOf<String>()
        var account = CloudAccount(
            id = AccountId("dbid:AAA"),
            provider = ProviderType.DROPBOX,
            displayName = "A Person",
            displayEmail = "person@example.com",
            grantedScopes = setOf("account_info.read", "files.metadata.read", "files.content.read"),
        )

        override val provider = ProviderType.DROPBOX
        override fun authorizationIntent(): Intent = error("not needed")

        override suspend fun complete(result: Intent?): CloudAccount {
            calls += "complete"
            return account
        }

        override suspend fun disconnect(account: AccountId) {
            calls += "revoke"
            if (failRevoke) throw CloudException(CloudErrorKind.TRANSIENT_NETWORK, "offline")
        }

        override fun rolesFor(grantedScopes: Set<String>) = AccountRoles(
            canBeSource = "files.content.read" in grantedScopes,
            canBeDestination = "files.content.write" in grantedScopes,
        )
    }

    private val connector = RecordingConnector()

    /**
     * Only reached for a provider with no connector — §7's fallback to what
     * §5 says the build can do at all. Drive declares itself destination-only,
     * which is what §8.2's `drive.file` actually permits.
     */
    private val providers = ProviderRegistry { type ->
        FakeCloudProvider(
            type = type,
            capabilities = FakeCloudProvider.defaultCapabilities(
                canBeSource = type != ProviderType.GOOGLE_DRIVE,
                canBeDestination = true,
            ),
        )
    }

    private val repository = AccountRepository(
        database,
        mapOf(ProviderType.DROPBOX to connector),
        providers,
        clock = { Instant.parse("2026-09-21T10:00:00Z") },
    )

    @Test
    fun `connecting records the metadata and nothing else`() = runTest {
        repository.completeConnection(ProviderType.DROPBOX, result = null)

        val row = assertNotNull(database.accounts.findById(AccountId("dbid:AAA")))
        assertEquals("A Person", row.displayName)
        assertEquals("person@example.com", row.displayEmail)
        // §7: what was granted, which the accounts row displays and §9 reads.
        assertEquals(
            setOf("account_info.read", "files.metadata.read", "files.content.read"),
            row.grantedScopes,
        )
    }

    @Test
    fun `a connected account is visible to the accounts screen`() = runTest {
        repository.completeConnection(ProviderType.DROPBOX, result = null)

        val listed = repository.observe().first()
        assertEquals(listOf(AccountId("dbid:AAA")), listed.map { it.id })
        assertEquals(setOf("files.content.read"), listed.single().grantedScopes.intersect(setOf("files.content.read")))
    }

    @Test
    fun `disconnect revokes before it forgets`() = runTest {
        // ADR-0028. The other order strands a live grant on the user's cloud
        // account with nothing left on the device that could revoke it.
        repository.completeConnection(ProviderType.DROPBOX, result = null)
        connector.calls.clear()

        repository.disconnect(AccountId("dbid:AAA"))

        assertEquals(listOf("revoke"), connector.calls)
        assertNull(database.accounts.findById(AccountId("dbid:AAA")))
    }

    @Test
    fun `a revoke that fails keeps the account`() = runTest {
        // An account still listed can be disconnected again when the network
        // comes back. A forgotten one cannot.
        repository.completeConnection(ProviderType.DROPBOX, result = null)
        connector.failRevoke = true

        assertThrows<CloudException> { repository.disconnect(AccountId("dbid:AAA")) }

        assertNotNull(database.accounts.findById(AccountId("dbid:AAA")), "the row was deleted despite the failure")
    }

    @Test
    fun `disconnecting an account that is not there does nothing`() = runTest {
        repository.disconnect(AccountId("dbid:missing"))

        assertTrue(connector.calls.isEmpty())
    }

    @Test
    fun `roles come from what was granted, not what was asked for`() = runTest {
        // §7. This account declined the write scope at the consent screen: it
        // is a real, usable account that cannot be a destination.
        repository.completeConnection(ProviderType.DROPBOX, result = null)

        val roles = repository.rolesFor(repository.observe().first().single())

        assertTrue(roles.canBeSource)
        assertTrue(!roles.canBeDestination)
    }

    @Test
    fun `a provider with no connector offers no connect`() = runTest {
        // Google Drive: §24.5 shows it as unsupported rather than being handed
        // a connector that throws when tapped.
        assertTrue(!repository.canConnect(ProviderType.GOOGLE_DRIVE))
        assertTrue(repository.canConnect(ProviderType.DROPBOX))
    }

    @Test
    fun `an account whose provider cannot interpret scopes falls back to declared capabilities`() = runTest {
        // There is no connector, so there was no grant in this build to read —
        // and inventing one would be worse than asking the adapter what it can
        // do at all. This is how the demo accounts get roles, and how a Drive
        // account would before its connector exists.
        val drive = CloudAccount(
            id = AccountId("drive:1"),
            provider = ProviderType.GOOGLE_DRIVE,
            displayName = null,
            displayEmail = null,
            grantedScopes = emptySet(),
        )

        val roles = repository.rolesFor(drive)

        assertTrue(!roles.canBeSource, "§8.2: drive.file cannot read an arbitrary source")
        assertTrue(roles.canBeDestination)
    }

    @Test
    fun `granted scopes for a provider nobody connected are empty, not an error`() = runTest {
        assertTrue(repository.grantedScopes(ProviderType.DROPBOX).isEmpty())
        assertNull(repository.connected(ProviderType.DROPBOX))
    }
}
