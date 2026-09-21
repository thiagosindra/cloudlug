package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The token endpoint's dialect, which is not the API's (§8.1, §8.3, §23).
 */
class DropboxTokenClientTest {

    private val server = MockWebServer()
    private val client = DropboxTokenClient(OkHttpClient(), tokenEndpoint = server.url("/oauth2/token").toString())
    private val challenge = PkceChallenge(verifier = "a-verifier", challenge = "a-challenge", state = "a-state")

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun sentForm(): Map<String, String> =
        server.takeRequest().body.readUtf8().split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }

    @Test
    fun `a code exchange sends the verifier and no client secret`() = runTest {
        respond(200, """{"access_token":"at","refresh_token":"rt","expires_in":14400,"scope":"files.content.read"}""")

        client.exchangeCode("the-code", challenge, redirectUri = "dev.thiagosindra.cloudlug://oauth/dropbox")

        val form = sentForm()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("a-verifier", form["code_verifier"])
        assertEquals(DropboxOAuth.APP_KEY, form["client_id"])
        // CloudLug is a public client. A secret here would have to ship in the
        // APK, where it is not a secret (§8.1).
        assertFalse("client_secret" in form, "a client secret was sent: ${form.keys}")
    }

    @Test
    fun `a grant carries what only the token response knows`() = runTest {
        respond(
            200,
            """{"access_token":"at","refresh_token":"rt","expires_in":14400,
               |"scope":"account_info.read files.content.read","account_id":"dbid:AAA"}""".trimMargin(),
        )

        val grant = client.exchangeCode("the-code", challenge)

        assertEquals("at", grant.accessToken)
        assertEquals("rt", grant.refreshToken)
        assertEquals(14400.seconds, grant.expiresIn)
        assertEquals(setOf("account_info.read", "files.content.read"), grant.grantedScopes)
        assertEquals("dbid:AAA", grant.accountId)
    }

    @Test
    fun `a refresh returns no refresh token, and says so rather than an empty one`() = runTest {
        // Dropbox keeps the refresh token it already issued. A caller that
        // stores this null over the real one disconnects the account four hours
        // later, so the type has to make the absence visible.
        respond(200, """{"access_token":"fresh","expires_in":14400,"token_type":"bearer"}""")

        val grant = client.refresh("stored-refresh-token")

        assertEquals("fresh", grant.accessToken)
        assertNull(grant.refreshToken)
        assertEquals("refresh_token", sentForm()["grant_type"])
    }

    @Test
    fun `an omitted scope means unchanged, not none`() = runTest {
        respond(200, """{"access_token":"fresh","expires_in":14400}""")

        assertTrue(client.refresh("stored").grantedScopes.isEmpty())
    }

    @Test
    fun `a revoked refresh token is AUTH_REQUIRED, not a retry`() = runTest {
        // The plain OAuth 2 dialect: "error" is a string here and an object
        // everywhere else in the Dropbox API. A parser that assumes the object
        // throws on the one response that most needs handling.
        respond(400, """{"error":"invalid_grant","error_description":"refresh token is invalid or revoked"}""")

        val failure = assertThrows<CloudException> { client.refresh("revoked") }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
    }

    @Test
    fun `a token response with no access token is not treated as a success`() = runTest {
        respond(200, """{"token_type":"bearer"}""")

        val failure = assertThrows<CloudException> { client.refresh("stored") }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
    }
}
