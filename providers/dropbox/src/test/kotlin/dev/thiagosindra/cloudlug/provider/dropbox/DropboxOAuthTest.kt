package dev.thiagosindra.cloudlug.provider.dropbox

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PKCE is the whole of CloudLug's protection for a public client, so these are
 * about the properties an attacker would attack, not about the happy path.
 */
class DropboxOAuthTest {

    @Test
    fun `the challenge matches RFC 7636's published example`() {
        // RFC 7636 appendix B. Deriving the expected value from our own code
        // would prove only that it is self-consistent.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DropboxOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `a verifier is long enough, short enough, and legal`() {
        repeat(50) {
            val challenge = DropboxOAuth.newChallenge()
            assertTrue(challenge.verifier.length in 43..128, "RFC 7636 §4.1: ${challenge.verifier.length}")
            assertTrue(
                challenge.verifier.all { it.isLetterOrDigit() || it in "-._~" },
                "verifier must be unreserved characters only: ${challenge.verifier}",
            )
        }
    }

    @Test
    fun `two attempts never share a verifier or a state`() {
        val challenges = List(200) { DropboxOAuth.newChallenge() }
        assertEquals(200, challenges.map { it.verifier }.toSet().size, "a verifier repeated")
        assertEquals(200, challenges.map { it.state }.toSet().size, "a state repeated")
    }

    @Test
    fun `the verifier is never sent with the authorization request`() {
        val challenge = DropboxOAuth.newChallenge(SecureRandom())
        val url = DropboxOAuth.authorizeUrl(challenge, redirectUri = "dev.thiagosindra.cloudlug://oauth/dropbox")

        assertFalse(challenge.verifier in url, "the PKCE secret leaked into the authorize URL")
        assertTrue("code_challenge=${challenge.challenge}" in url)
        assertTrue("code_challenge_method=S256" in url)
    }

    @Test
    fun `offline access is always requested`() {
        // §8.3. Without it Dropbox issues no refresh token and the account
        // stops working a few hours after the user connects it.
        assertTrue("token_access_type=offline" in DropboxOAuth.authorizeUrl(DropboxOAuth.newChallenge()))
    }

    @Test
    fun `the terminal flow sends no redirect uri and no state`() {
        val url = DropboxOAuth.authorizeUrl(DropboxOAuth.newChallenge())
        assertFalse("redirect_uri" in url, "a redirect would stop Dropbox showing the code on screen")
        assertFalse("state=" in url, "state protects a redirect; there is no redirect here")
    }

    @Test
    fun `no exchange ever carries a client secret`() {
        val challenge = DropboxOAuth.newChallenge()
        val code = DropboxOAuth.codeExchangeForm("auth-code", challenge)
        val refresh = DropboxOAuth.refreshForm("refresh-token")

        assertFalse(code.keys.any { "secret" in it }, "PKCE means there is no secret to send: $code")
        assertFalse(refresh.keys.any { "secret" in it }, "PKCE means there is no secret to send: $refresh")
        assertEquals(challenge.verifier, code["code_verifier"])
    }

    @Test
    fun `a redirect carrying the wrong state is rejected`() {
        val challenge = DropboxOAuth.newChallenge()
        assertTrue(DropboxOAuth.statesMatch(challenge.state, challenge.state))
        assertFalse(DropboxOAuth.statesMatch(challenge.state, null), "§8.4: a missing state is not a match")
        assertFalse(DropboxOAuth.statesMatch(challenge.state, "something-else"))
        assertFalse(
            DropboxOAuth.statesMatch(challenge.state, challenge.state.dropLast(1)),
            "a truncated state is not a match",
        )
    }

    @Test
    fun `scopes are exactly the four that were granted`() {
        // §8.2 and §7: asking for more than this would show up on the consent
        // screen and in grantedScopes, and CloudLug needs none of it.
        assertEquals(
            listOf("account_info.read", "files.metadata.read", "files.content.read", "files.content.write"),
            DropboxOAuth.SCOPES,
        )
    }
}
