package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.PkceChallenge

/**
 * Google OAuth 2 with PKCE, for the app and for the tools that drive it by hand.
 *
 * Pure JVM, like `DropboxOAuth`, so `tools/drive-auth` runs the same forms the
 * app will send and a person has watched them work before any adapter code
 * depends on them.
 *
 * ### Two clients, one project
 *
 * The **Android** client ([ANDROID_CLIENT_ID]) is what the app uses. It has no
 * secret: Google issues none for an Android client, which is keyed instead on
 * the package name and the debug certificate's SHA-1 (`docs/oauth.md`).
 *
 * The **Desktop** client belongs to the tools, not to this object, and is never
 * compiled into the app. Google requires its `client_secret` at the token
 * endpoint even under PKCE, so the tools pass one in from
 * `DRIVE_TOOL_CLIENT_SECRET` at runtime — which is why every form here takes an
 * optional secret rather than none. Callers must not assume a form without one
 * works for a Desktop client, or that one with it belongs anywhere near an APK.
 */
object GoogleOAuth {

    /** Public by design: it is in every authorization URL a user sees. */
    const val ANDROID_CLIENT_ID = "17997718186-fjt7n8oehpagku3dio8rcqt0fbc5ssuh.apps.googleusercontent.com"

    /**
     * The Android client's redirect: Google's reverse-client-id custom scheme,
     * claimed by the app's manifest and validated against the pending PKCE
     * state on the way back (§8.4).
     *
     * Google documents custom schemes as unsupported for Android clients; this
     * one works because the scheme is enabled on the client in the console,
     * and Google may withdraw that (spec-proposals/v1.6.md §4).
     */
    const val ANDROID_REDIRECT_SCHEME = "com.googleusercontent.apps.17997718186-fjt7n8oehpagku3dio8rcqt0fbc5ssuh"
    const val ANDROID_REDIRECT_URI = "$ANDROID_REDIRECT_SCHEME:/oauth2redirect"

    const val AUTHORIZE_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    /**
     * §8.2: non-sensitive, and enough to be a destination — CloudLug creates the
     * folder and every file in it, so it can read them back for §21.
     */
    const val SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"

    /**
     * Restricted (annual CASA for a published app). Never requested by this
     * build; named so [rolesFor] can recognise a self-builder's grant (§8.2
     * item 3).
     */
    const val SCOPE_READONLY = "https://www.googleapis.com/auth/drive.readonly"

    /** Restricted; implies both roles if a self-builder grants it. */
    const val SCOPE_FULL = "https://www.googleapis.com/auth/drive"

    val SCOPES = listOf(SCOPE_FILE)

    /**
     * §7 and §8.2 item 3: roles follow what was *granted*.
     *
     * `drive.file` alone is destination-only, and that is the whole of what
     * this build asks for. Reading arbitrary files — being a source — needs
     * `drive.readonly` or `drive`, so the same adapter serves a self-build that
     * has them without a code change.
     */
    fun rolesFor(grantedScopes: Set<String>): AccountRoles = AccountRoles(
        canBeSource = SCOPE_READONLY in grantedScopes || SCOPE_FULL in grantedScopes,
        canBeDestination = SCOPE_FILE in grantedScopes || SCOPE_FULL in grantedScopes,
    )

    /** Google reports what it granted as one space-separated `scope` string. */
    fun parseScopes(scope: String?): Set<String> =
        scope.orEmpty().split(' ').filter { it.isNotBlank() }.toSet()

    fun authorizeUrl(
        clientId: String,
        challenge: PkceChallenge,
        redirectUri: String,
        scopes: List<String> = SCOPES,
    ): String {
        val parameters = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.joinToString(" "),
            "code_challenge" to challenge.challenge,
            "code_challenge_method" to challenge.method,
            "state" to challenge.state,
            // §8.3: Google's equivalent of Dropbox's token_access_type=offline.
            // `access_type=offline` asks for a refresh token; `prompt=consent`
            // makes Google issue one even when this user has consented before,
            // which it otherwise silently declines to do — a reconnect after a
            // disconnect would then come back with no refresh token at all.
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return AUTHORIZE_ENDPOINT + "?" + parameters.joinToString("&") { (k, v) -> "$k=${Pkce.urlEncode(v)}" }
    }

    fun codeExchangeForm(
        clientId: String,
        code: String,
        challenge: PkceChallenge,
        redirectUri: String,
        clientSecret: String? = null,
    ): Map<String, String> = buildMap {
        put("grant_type", "authorization_code")
        put("code", code)
        put("client_id", clientId)
        put("code_verifier", challenge.verifier)
        put("redirect_uri", redirectUri)
        if (clientSecret != null) put("client_secret", clientSecret)
    }

    /** §8.3: a stored refresh token for a short-lived access token. */
    fun refreshForm(clientId: String, refreshToken: String, clientSecret: String? = null): Map<String, String> =
        buildMap {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", clientId)
            if (clientSecret != null) put("client_secret", clientSecret)
        }

    /** §8.3: revoking the refresh token revokes the whole grant. */
    fun revokeForm(token: String): Map<String, String> = mapOf("token" to token)
}
