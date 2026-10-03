package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.RefreshTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class StoredDriveTokenSourceTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private var clock = 0L
    private val source = StoredDriveTokenSource(
        tokens = DriveTokenClient(localClient(server), tokenEndpoint = server.url("/token").toString(), revokeEndpoint = server.url("/revoke").toString()),
        store = store,
        scopes = { emptySet() },
        now = { clock },
    )

    private val alice = AccountId("alice")
    private val bob = AccountId("bob")

    @AfterTest
    fun stop() = server.shutdown()

    private fun grant(access: String, refresh: String? = "refresh-$access") =
        DriveGrant(access, refresh, 1.hours, setOf(GoogleOAuth.SCOPE_FILE))

    @Test
    fun `nothing is stored until the grant has an account`() = runTest {
        source.adopt(grant("a1"))
        assertEquals(emptyMap(), store.values, "an abandoned connect must leave no credential behind")

        source.bindPending(alice)

        assertEquals("refresh-a1", store.values[alice])
        assertEquals("a1", source.accessToken(alice))
        assertEquals(0, server.requestCount, "the access token in hand is used, not refreshed")
    }

    @Test
    fun `two accounts keep their own credentials`() = runTest {
        source.adopt(grant("a1"))
        source.bindPending(alice)
        source.adopt(grant("b1"))
        source.bindPending(bob)

        assertEquals("a1", source.accessToken(alice))
        assertEquals("b1", source.accessToken(bob))
        assertEquals(setOf(alice, bob), store.values.keys)
    }

    @Test
    fun `a grant with no refresh token is refused rather than kept for an hour`() = runTest {
        source.adopt(grant("a1", refresh = null))
        val failure = assertFailsWith<CloudException> { source.bindPending(alice) }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
        assertNull(store.values[alice])
    }

    @Test
    fun `an expired access token is refreshed, and a refresh with no refresh token keeps the stored one`() = runTest {
        source.adopt(grant("a1"))
        source.bindPending(alice)
        clock += 2.hours.inWholeMilliseconds
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","expires_in":3599,"scope":"${GoogleOAuth.SCOPE_FILE}","token_type":"Bearer"}"""))

        assertEquals("a2", source.accessToken(alice))
        assertEquals("refresh-a1", store.values[alice])
        assertEquals("refresh_token", server.takeRequest().body.readUtf8().let { Regex("grant_type=([a-z_]+)").find(it)!!.groupValues[1] })
    }

    @Test
    fun `a refresh answered invalid_grant is AUTH_REQUIRED`() = runTest {
        source.adopt(grant("a1"))
        source.bindPending(alice)
        clock += 2.hours.inWholeMilliseconds
        server.enqueue(DriveFixtures.response("token_refresh_invalid_grant_400"))

        assertEquals(CloudErrorKind.AUTH_REQUIRED, assertFailsWith<CloudException> { source.accessToken(alice) }.kind)
    }

    @Test
    fun `revoke sends the refresh token to Google's revocation endpoint`() = runTest {
        source.adopt(grant("a1"))
        source.bindPending(alice)
        server.enqueue(MockResponse().setResponseCode(200))

        source.revoke(alice)

        val request = server.takeRequest()
        assertEquals("/revoke", request.path)
        assertEquals("token=refresh-a1", request.body.readUtf8())
    }

    private class MemoryStore : RefreshTokenStore {
        val values = mutableMapOf<AccountId, String>()
        override fun read(account: AccountId) = values[account]
        override fun write(account: AccountId, token: String) {
            values[account] = token
        }
        override fun clear(account: AccountId) {
            values.remove(account)
        }
    }
}
