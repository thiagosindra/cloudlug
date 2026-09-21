package dev.thiagosindra.cloudlug.auth

import android.content.Intent
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
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
    private val repository = AccountRepository(
        database,
        mapOf(ProviderType.DROPBOX to connector),
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

        val roles = assertNotNull(repository.rolesFor(repository.observe().first().single()))

        assertTrue(roles.canBeSource)
        assertTrue(!roles.canBeDestination)
    }

    @Test
    fun `a provider with no connector has no roles and offers no connect`() = runTest {
        // Google Drive until v0.4: §24.5 shows it as unsupported rather than
        // being handed a connector that throws when tapped.
        assertTrue(!repository.canConnect(ProviderType.GOOGLE_DRIVE))
        assertTrue(repository.canConnect(ProviderType.DROPBOX))

        val drive = CloudAccount(
            id = AccountId("drive:1"),
            provider = ProviderType.GOOGLE_DRIVE,
            displayName = null,
            displayEmail = null,
            grantedScopes = setOf("drive.file"),
        )
        assertNull(repository.rolesFor(drive))
    }

    @Test
    fun `granted scopes for a provider nobody connected are empty, not an error`() = runTest {
        assertTrue(repository.grantedScopes(ProviderType.DROPBOX).isEmpty())
        assertNull(repository.connected(ProviderType.DROPBOX))
    }
}
