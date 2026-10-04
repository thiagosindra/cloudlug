package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.provider.Pkce
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleOAuthTest {

    private val challenge = Pkce.newChallenge()

    private fun query(url: String): Map<String, String> =
        URI.create(url).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun `the authorize URL asks for drive_file only, offline, with a fresh consent`() {
        val q = query(GoogleOAuth.authorizeUrl("client", challenge, "http://127.0.0.1:1234"))

        assertEquals(GoogleOAuth.SCOPE_FILE, q["scope"])
        assertEquals("offline", q["access_type"])
        assertEquals("consent", q["prompt"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(challenge.challenge, q["code_challenge"])
        assertEquals(challenge.state, q["state"])
        assertEquals("http://127.0.0.1:1234", q["redirect_uri"])
        // The verifier is the secret half; it must never reach the browser.
        assertFalse(challenge.verifier in URI.create(GoogleOAuth.authorizeUrl("client", challenge, "x:/y")).rawQuery)
    }

    @Test
    fun `the Android client's forms carry no secret, and a tool's carry the one it was given`() {
        val app = GoogleOAuth.codeExchangeForm(GoogleOAuth.ANDROID_CLIENT_ID, "code", challenge, "x:/y")
        assertNull(app["client_secret"])
        assertEquals(challenge.verifier, app["code_verifier"])

        val tool = GoogleOAuth.refreshForm("desktop", "refresh", clientSecret = "from-env")
        assertEquals("from-env", tool["client_secret"])
        assertNull(GoogleOAuth.refreshForm("android", "refresh")["client_secret"])
    }

    @Test
    fun `drive_file alone is a destination and not a source`() {
        val roles = GoogleOAuth.rolesFor(GoogleOAuth.parseScopes(GoogleOAuth.SCOPE_FILE))
        assertTrue(roles.canBeDestination)
        assertFalse(roles.canBeSource)
        assertTrue(roles.seesOnlyOwnObjects, "drive.file cannot see the user's own folders (v1.6 §1)")
    }

    @Test
    fun `a self-builder's readonly grant makes the same account a source`() {
        val roles = GoogleOAuth.rolesFor(
            GoogleOAuth.parseScopes("${GoogleOAuth.SCOPE_FILE} ${GoogleOAuth.SCOPE_READONLY}"),
        )
        assertTrue(roles.canBeSource)
        assertTrue(roles.canBeDestination)
        assertFalse(roles.seesOnlyOwnObjects)
    }

    @Test
    fun `nothing granted is nothing permitted`() {
        assertTrue(GoogleOAuth.rolesFor(GoogleOAuth.parseScopes(null)).canDoNothing)
        assertTrue(GoogleOAuth.rolesFor(GoogleOAuth.parseScopes("openid email")).canDoNothing)
    }

    @Test
    fun `the redirect scheme is the Android client id reversed, as Google requires`() {
        // A typo here fails only on a phone, at the consent screen, with an
        // error that names neither the scheme nor the client.
        val idPart = GoogleOAuth.ANDROID_CLIENT_ID.removeSuffix(".apps.googleusercontent.com")
        assertEquals("com.googleusercontent.apps.$idPart", GoogleOAuth.ANDROID_REDIRECT_SCHEME)
        assertEquals("${GoogleOAuth.ANDROID_REDIRECT_SCHEME}:/oauth2redirect", GoogleOAuth.ANDROID_REDIRECT_URI)
    }
}
