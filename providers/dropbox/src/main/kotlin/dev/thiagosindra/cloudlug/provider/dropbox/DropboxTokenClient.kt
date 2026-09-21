package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One response from Dropbox's token endpoint (§8.1, §8.3).
 *
 * [refreshToken] is present when a code is exchanged and absent when one is
 * refreshed — Dropbox reissues the access token and keeps the refresh token it
 * already gave you. A caller that overwrites its stored refresh token with a
 * refresh response's null therefore disconnects the account about four hours
 * later, which is a fault that looks like a Dropbox outage.
 *
 * [grantedScopes] is §7's answer and only Dropbox can give it: the user may
 * decline a scope at the consent screen, and no endpoint reports what was
 * granted afterwards. It is empty when the response omits `scope`, which means
 * "unchanged", not "none".
 */
data class DropboxGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Duration,
    val grantedScopes: Set<String>,
    val accountId: String?,
)

/**
 * Dropbox's token endpoint, which is not part of the API [DropboxApi] speaks.
 *
 * It is a different dialect in three ways, and each one has bitten this code:
 * the request is form-encoded rather than JSON, the errors are plain OAuth 2
 * (`{"error":"invalid_grant"}`, a string where the API puts an object), and
 * there is no `Authorization` header because the token being asked for is the
 * credential. Keeping it out of [DropboxApi] keeps that class's invariant —
 * every request is authorized — actually true.
 *
 * [tokenEndpoint] is injectable so tests can point it at a local server. It is
 * never read from user input.
 */
class DropboxTokenClient(
    private val client: OkHttpClient,
    private val tokenEndpoint: String = DropboxOAuth.TOKEN_ENDPOINT,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** §8.1: the authorization code, plus the PKCE verifier that proves it is ours. */
    suspend fun exchangeCode(
        code: String,
        challenge: PkceChallenge,
        redirectUri: String? = null,
    ): DropboxGrant = post(DropboxOAuth.codeExchangeForm(code, challenge, redirectUri))

    /**
     * §8.3: a stored refresh token for a fresh access token.
     *
     * A revoked or tampered refresh token comes back as `invalid_grant`, which
     * §23 maps to `AUTH_REQUIRED` — the user reconnects, and no amount of
     * retrying will help.
     */
    suspend fun refresh(refreshToken: String): DropboxGrant = post(DropboxOAuth.refreshForm(refreshToken))

    private fun post(form: Map<String, String>): DropboxGrant {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(DropboxOAuth.formBody(form).toRequestBody(FORM_MEDIA_TYPE))
            .build()

        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DropboxErrors.toException(response.code, text)
            parse(text)
        }
    }

    private fun parse(body: String): DropboxGrant {
        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            // Not the body's contents: it is a token response, and §26 keeps it
            // out of anything that might be read later.
            throw CloudException(CloudErrorKind.PERMANENT, "Dropbox returned a token response that is not JSON")
        }

        fun string(name: String) = fields[name]?.jsonPrimitive?.contentOrNullIfBlank()

        return DropboxGrant(
            accessToken = string("access_token")
                ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Dropbox returned no access_token"),
            refreshToken = string("refresh_token"),
            // Dropbox sends about four hours. The default is deliberately short
            // rather than long: refreshing early costs one request, and
            // refreshing late fails a transfer that was running unattended.
            expiresIn = (fields["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
                .coerceAtLeast(0L).seconds,
            grantedScopes = string("scope")?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            accountId = string("account_id"),
        )
    }

    private companion object {
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        fun kotlinx.serialization.json.JsonPrimitive.contentOrNullIfBlank(): String? =
            content.takeIf { it.isNotBlank() }
    }
}
