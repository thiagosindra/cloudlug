package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.model.AccountId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * §8.3's token handling, on the JVM because that is where its failure modes
 * are: an account that silently stops working four hours after it was
 * connected is a bug nobody notices until a transfer dies unattended.
 */
class StoredDropboxTokenSourceTest {

    private val server = MockWebServer()
    private val client = DropboxTokenClient(OkHttpClient(), tokenEndpoint = server.url("/oauth2/token").toString())

    private var clock = 0L

    /** Account-keyed, like the real one, so a cross-account leak shows up. */
    private val stored = object : DropboxRefreshTokenStore {
        val tokens = mutableMapOf(SOURCE to "the-refresh-token")
        override fun read(account: AccountId) = tokens[account]
        override fun write(account: AccountId, token: String) { tokens[account] = token }
        override fun clear(account: AccountId) { tokens.remove(account) }
    }

    private fun source(scopes: Set<String> = emptySet()) =
        StoredDropboxTokenSource(client, stored, scopes = { scopes }, now = { clock })

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun respondWithToken(accessToken: String, refreshToken: String? = null, expiresIn: Long = 14400) {
        val refresh = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
        server.enqueue(
            MockResponse().setBody("""{"access_token":"$accessToken","expires_in":$expiresIn$refresh}"""),
        )
    }

    @Test
    fun `the first call exchanges the stored refresh token`() = runTest {
        respondWithToken("an-access-token")

        assertEquals("an-access-token", source().accessToken(SOURCE))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a live token is reused rather than refetched`() = runTest {
        respondWithToken("an-access-token")
        val source = source()

        source.accessToken(SOURCE)
        clock += 1.hours.inWholeMilliseconds
        assertEquals("an-access-token", source.accessToken(SOURCE))

        // A second enqueued response would have been consumed if it asked again.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a token is refreshed before it expires, not after`() = runTest {
        respondWithToken("first", expiresIn = 14400)
        respondWithToken("second", expiresIn = 14400)
        val source = source()

        source.accessToken(SOURCE)
        // Four hours less three minutes: Dropbox would still accept it, but
        // it is inside the five-minute skew. A token that expires mid-upload
        // fails a transfer that has been running unattended.
        clock += (4.hours - 3.minutes).inWholeMilliseconds

        assertEquals("second", source.accessToken(SOURCE))
    }

    @Test
    fun `a refresh that returns no refresh token leaves the stored one alone`() = runTest {
        // Dropbox reissues the access token and keeps the refresh token. This
        // is the four-hour disconnection: store the absent one and the next
        // refresh has nothing to send.
        respondWithToken("fresh", refreshToken = null)

        source().accessToken(SOURCE)

        assertEquals("the-refresh-token", stored.tokens[SOURCE])
    }

    @Test
    fun `a rotated refresh token replaces the stored one`() = runTest {
        respondWithToken("fresh", refreshToken = "rotated")

        source().accessToken(SOURCE)

        assertEquals("rotated", stored.tokens[SOURCE])
    }

    @Test
    fun `an account with no stored credential is AUTH_REQUIRED and asks nobody`() = runTest {
        // §8.3: an unreadable credential reads as absent, and this is what
        // absent has to do — not hang, not retry, not reach the network.
        stored.tokens.remove(SOURCE)

        val failure = assertThrows<CloudException> { source().accessToken(SOURCE) }

        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `simultaneous callers refresh once between them`() = runTest {
        // What a resumed transfer does: several items reach for a token at the
        // same moment. One refresh, not one per item.
        respondWithToken("shared")
        val source = source()

        val tokens = (1..8).map { async { source.accessToken(SOURCE) } }.awaitAll()

        assertEquals(List(8) { "shared" }, tokens)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the access token is never written to the store`() = runTest {
        // §8.3 keeps access tokens in memory. The only thing that may reach
        // durable storage is the refresh token.
        respondWithToken("an-access-token", refreshToken = "rotated")

        source().accessToken(SOURCE)

        assertEquals("rotated", stored.tokens[SOURCE])
        assertTrue(stored.tokens.values.none { it == "an-access-token" })
    }

    @Test
    fun `adopting a fresh grant costs no refresh`() = runTest {
        // Dropbox handed over an access token in the same response as the
        // refresh token. Spending the refresh token to ask for one again would
        // be a wasted round trip while the user watches a spinner.
        val source = source()

        source.adopt(
            DropboxGrant(
                accessToken = "from-the-grant",
                refreshToken = "rotated",
                expiresIn = 4.hours,
                grantedScopes = setOf("files.content.read"),
                accountId = SOURCE.value,
            ),
        )

        assertEquals("from-the-grant", source.accessToken(SOURCE))
        assertEquals("rotated", stored.tokens[SOURCE])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an adopted grant answers for the scopes before any account row exists`() = runTest {
        // §7's granted scopes arrive once, in the token response. At that
        // moment there is no account record to read them back from, and
        // Dropbox has no endpoint that will repeat them.
        val source = source(scopes = emptySet())

        source.adopt(
            DropboxGrant(
                accessToken = "at",
                refreshToken = "rt",
                expiresIn = 4.hours,
                grantedScopes = setOf("account_info.read", "files.content.read"),
                accountId = SOURCE.value,
            ),
        )

        assertEquals(setOf("account_info.read", "files.content.read"), source.grantedScopes(SOURCE))
    }

    @Test
    fun `granted scopes otherwise come from the account record`() = runTest {
        assertEquals(setOf("files.metadata.read"), source(scopes = setOf("files.metadata.read")).grantedScopes(SOURCE))
    }

    @Test
    fun `two accounts do not share a token`() = runTest {
        // What v0.4 is for. A Dropbox-to-Dropbox transfer reads from one
        // account and writes to the other at the same moment, so a single
        // cached token would have had the destination answering for the source.
        stored.tokens[DESTINATION] = "the-other-refresh-token"
        respondWithToken("source-token")
        respondWithToken("destination-token")
        val source = source()

        assertEquals("source-token", source.accessToken(SOURCE))
        assertEquals("destination-token", source.accessToken(DESTINATION))

        // And each stays its own on the next call, rather than the later one
        // having overwritten the earlier.
        assertEquals("source-token", source.accessToken(SOURCE))
        assertEquals("destination-token", source.accessToken(DESTINATION))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rotated refresh token is filed against the account it came from`() = runTest {
        stored.tokens[DESTINATION] = "the-other-refresh-token"
        respondWithToken("fresh", refreshToken = "rotated")

        source().accessToken(DESTINATION)

        assertEquals("rotated", stored.tokens[DESTINATION])
        assertEquals("the-refresh-token", stored.tokens[SOURCE], "the other account's credential moved")
    }

    @Test
    fun `an account with no credential fails even while another is connected`() = runTest {
        // The asymmetry worth having a test for: one connected account must not
        // make a second one look connected.
        val failure = assertThrows<CloudException> { source().accessToken(DESTINATION) }

        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `adopting a grant records which account authenticate is about`() = runTest {
        // authenticate() is the one call with no AccountId, because finding one
        // is its job. Dropbox puts account_id in the token response, so the
        // answer is known before anyone asks who the user is.
        val source = source()

        source.adopt(
            DropboxGrant(
                accessToken = "at",
                refreshToken = "rt",
                expiresIn = 4.hours,
                grantedScopes = setOf("files.content.read"),
                accountId = DESTINATION.value,
            ),
        )

        assertEquals(DESTINATION, source.accountJustConnected())
        assertEquals("rt", stored.tokens[DESTINATION])
    }

    @Test
    fun `a grant Dropbox filed against nobody is refused`() = runTest {
        // Without an account_id there is no key to store the credential under,
        // and a credential written to the wrong account is worse than none.
        assertThrows<IllegalArgumentException> {
            source().adopt(
                DropboxGrant(
                    accessToken = "at",
                    refreshToken = "rt",
                    expiresIn = 4.hours,
                    grantedScopes = emptySet(),
                    accountId = null,
                ),
            )
        }
    }

    private companion object {
        val SOURCE = AccountId("dbid:AAA")
        val DESTINATION = AccountId("dbid:BBB")
    }
}
