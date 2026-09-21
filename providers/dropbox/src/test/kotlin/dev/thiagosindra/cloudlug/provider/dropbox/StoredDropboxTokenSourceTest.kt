package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
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
    private val stored = object : DropboxRefreshTokenStore {
        var token: String? = "the-refresh-token"
        override fun read() = token
        override fun write(token: String) { this.token = token }
        override fun clear() { token = null }
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

        assertEquals("an-access-token", source().accessToken())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a live token is reused rather than refetched`() = runTest {
        respondWithToken("an-access-token")
        val source = source()

        source.accessToken()
        clock += 1.hours.inWholeMilliseconds
        assertEquals("an-access-token", source.accessToken())

        // A second enqueued response would have been consumed if it asked again.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a token is refreshed before it expires, not after`() = runTest {
        respondWithToken("first", expiresIn = 14400)
        respondWithToken("second", expiresIn = 14400)
        val source = source()

        source.accessToken()
        // Four hours less three minutes: Dropbox would still accept it, but
        // it is inside the five-minute skew. A token that expires mid-upload
        // fails a transfer that has been running unattended.
        clock += (4.hours - 3.minutes).inWholeMilliseconds

        assertEquals("second", source.accessToken())
    }

    @Test
    fun `a refresh that returns no refresh token leaves the stored one alone`() = runTest {
        // Dropbox reissues the access token and keeps the refresh token. This
        // is the four-hour disconnection: store the absent one and the next
        // refresh has nothing to send.
        respondWithToken("fresh", refreshToken = null)

        source().accessToken()

        assertEquals("the-refresh-token", stored.token)
    }

    @Test
    fun `a rotated refresh token replaces the stored one`() = runTest {
        respondWithToken("fresh", refreshToken = "rotated")

        source().accessToken()

        assertEquals("rotated", stored.token)
    }

    @Test
    fun `an account with no stored credential is AUTH_REQUIRED and asks nobody`() = runTest {
        // §8.3: an unreadable credential reads as absent, and this is what
        // absent has to do — not hang, not retry, not reach the network.
        stored.token = null

        val failure = assertThrows<CloudException> { source().accessToken() }

        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `simultaneous callers refresh once between them`() = runTest {
        // What a resumed transfer does: several items reach for a token at the
        // same moment. One refresh, not one per item.
        respondWithToken("shared")
        val source = source()

        val tokens = (1..8).map { async { source.accessToken() } }.awaitAll()

        assertEquals(List(8) { "shared" }, tokens)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the access token is never written to the store`() = runTest {
        // §8.3 keeps access tokens in memory. The only thing that may reach
        // durable storage is the refresh token.
        respondWithToken("an-access-token", refreshToken = "rotated")

        source().accessToken()

        assertEquals("rotated", stored.token)
        assertNull(stored.token?.takeIf { it == "an-access-token" })
    }
}
