package dev.thiagosindra.cloudlug.provider.dropbox

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The PKCE material for one authorization attempt (RFC 7636).
 *
 * [verifier] is the secret. It never leaves the device and never appears in a
 * log (§26 redacts `code_verifier` by name); only [challenge] is sent with the
 * authorization request, and only [verifier] is sent with the exchange. That is
 * the whole point of PKCE: an attacker who intercepts the redirect gets a code
 * that is useless without a secret they never saw.
 *
 * [state] is unrelated to PKCE and exists for §8.4: the redirect that comes back
 * must carry the state this attempt sent, or it is not ours and the code in it
 * is not trusted.
 */
data class PkceChallenge(
    val verifier: String,
    val challenge: String,
    val state: String,
    val method: String = "S256",
)

/**
 * Dropbox OAuth 2 with PKCE and no client secret.
 *
 * CloudLug is a public client: the APK is readable, so any secret shipped in it
 * is not a secret. §8.1 says PKCE for exactly this reason, and there is no
 * secret anywhere in this repository or in the app — the app key below is a
 * public identifier, not a credential.
 *
 * Pure JVM on purpose. The Android integration drives AppAuth with these
 * values, and `tools/dropbox-auth` drives the same code from a terminal, so the
 * PKCE logic the app depends on is the logic a human has already run by hand.
 */
object DropboxOAuth {

    /**
     * The registered app key. Public by design: it identifies the app to
     * Dropbox and is visible in every authorization URL a user ever sees.
     */
    const val APP_KEY = "spv3k58wyxixvz4"

    const val AUTHORIZE_ENDPOINT = "https://www.dropbox.com/oauth2/authorize"
    const val TOKEN_ENDPOINT = "https://api.dropboxapi.com/oauth2/token"
    const val REVOKE_ENDPOINT = "https://api.dropboxapi.com/2/auth/token/revoke"

    /** §8.2: the least CloudLug can ask for and still move files both ways. */
    val SCOPES = listOf(
        "account_info.read",
        "files.metadata.read",
        "files.content.read",
        "files.content.write",
    )

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * A fresh verifier, challenge and state.
     *
     * The verifier is 32 random bytes rendered base64url, which lands at 43
     * characters — the shortest RFC 7636 allows, and well inside its 128
     * ceiling. Base64url is used rather than picking from the unreserved set by
     * hand because a hand-rolled alphabet is where modulo bias creeps in.
     */
    fun newChallenge(random: SecureRandom = SecureRandom()): PkceChallenge {
        val verifier = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        return PkceChallenge(
            verifier = verifier,
            challenge = challengeFor(verifier),
            state = encoder.encodeToString(ByteArray(16).also(random::nextBytes)),
        )
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded — RFC 7636 §4.2. */
    fun challengeFor(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * The URL to open in a browser.
     *
     * [redirectUri] is null for the terminal flow, where Dropbox shows the code
     * on screen for the user to copy. The app passes its registered URI, and
     * §8.4 then requires the returned state to match [challenge]'s.
     */
    fun authorizeUrl(challenge: PkceChallenge, redirectUri: String? = null): String {
        val parameters = buildList {
            add("client_id" to APP_KEY)
            add("response_type" to "code")
            // §8.3: without this Dropbox issues no refresh token, and the
            // account silently stops working about four hours later.
            add("token_access_type" to "offline")
            add("code_challenge" to challenge.challenge)
            add("code_challenge_method" to challenge.method)
            add("scope" to SCOPES.joinToString(" "))
            if (redirectUri != null) {
                add("redirect_uri" to redirectUri)
                add("state" to challenge.state)
            }
        }
        return AUTHORIZE_ENDPOINT + "?" + parameters.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    }

    /**
     * Form fields for exchanging an authorization code.
     *
     * No `client_secret`: there is none, and adding one would mean shipping it
     * in the APK.
     */
    fun codeExchangeForm(code: String, challenge: PkceChallenge, redirectUri: String? = null): Map<String, String> =
        buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("client_id", APP_KEY)
            put("code_verifier", challenge.verifier)
            if (redirectUri != null) put("redirect_uri", redirectUri)
        }

    /** §8.3: exchanging the stored refresh token for a short-lived access token. */
    fun refreshForm(refreshToken: String): Map<String, String> = mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to APP_KEY,
    )

    fun formBody(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

    /**
     * §8.4: a redirect is only ours if it carries the state we sent.
     *
     * Constant-time so a mismatch cannot be probed by timing, which costs
     * nothing here and removes the need to think about it again.
     */
    fun statesMatch(expected: String, returned: String?): Boolean {
        if (returned == null || expected.length != returned.length) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), returned.toByteArray(Charsets.UTF_8))
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
