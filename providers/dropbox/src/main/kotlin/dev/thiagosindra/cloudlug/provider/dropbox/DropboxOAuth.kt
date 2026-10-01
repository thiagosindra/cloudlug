package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.Pkce
import java.security.SecureRandom

/**
 * Moved to `:providers:api` in v0.6 so Google Drive runs the same RFC 7636 code;
 * the alias keeps every Dropbox caller compiling unchanged.
 */
typealias PkceChallenge = dev.thiagosindra.cloudlug.provider.PkceChallenge

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

    /** A fresh verifier, challenge and state; see [Pkce.newChallenge]. */
    fun newChallenge(random: SecureRandom = SecureRandom()): PkceChallenge = Pkce.newChallenge(random)

    /**
     * §7: what these granted scopes let an account do.
     *
     * Reading takes both of Dropbox's read scopes. `files.metadata.read` walks
     * a tree and `files.content.read` fetches the bytes, so an account holding
     * only the first can enumerate a whole selection and then fail on the first
     * file — a worse outcome than saying up front that it cannot be a source.
     *
     * Asking what was granted rather than what was requested is the point: a
     * user may decline a scope at the consent screen, and Dropbox will happily
     * issue a token for the rest.
     */
    fun rolesFor(grantedScopes: Set<String>): AccountRoles = AccountRoles(
        canBeSource = "files.metadata.read" in grantedScopes && "files.content.read" in grantedScopes,
        canBeDestination = "files.content.write" in grantedScopes,
    )

    fun challengeFor(verifier: String): String = Pkce.challengeFor(verifier)

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

    fun formBody(fields: Map<String, String>): String = Pkce.formBody(fields)

    /** §8.4; see [Pkce.statesMatch]. */
    fun statesMatch(expected: String, returned: String?): Boolean = Pkce.statesMatch(expected, returned)

    private fun urlEncode(value: String): String = Pkce.urlEncode(value)
}
