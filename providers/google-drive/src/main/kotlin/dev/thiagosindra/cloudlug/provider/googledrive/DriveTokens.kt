package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.PkceChallenge
import dev.thiagosindra.cloudlug.provider.RefreshTokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One response from Google's token endpoint (§8.1, §8.3).
 *
 * Unlike Dropbox's, it names no account: with `drive.file` alone there is no
 * id token, so who the user is comes from `about.get` afterwards. That is why
 * [StoredDriveTokenSource] holds a fresh grant as *pending* until
 * [GoogleDriveCloudProvider.authenticate] can say whose it is.
 *
 * [refreshToken] is present on a code exchange made with `access_type=offline`
 * and `prompt=consent`, and usually absent on a refresh.
 */
data class DriveGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Duration,
    val grantedScopes: Set<String>,
)

/**
 * Google's token and revocation endpoints, for the Android client (no secret).
 *
 * Moves to [io] before blocking, for the reason `DropboxTokenClient` records:
 * the accounts screen calls this from `Dispatchers.Main`.
 */
class DriveTokenClient(
    private val client: OkHttpClient,
    private val tokenEndpoint: String = GoogleOAuth.TOKEN_ENDPOINT,
    private val revokeEndpoint: String = GoogleOAuth.REVOKE_ENDPOINT,
    private val clientId: String = GoogleOAuth.ANDROID_CLIENT_ID,
    /** Null for the app. Only the live tests set one, for the Desktop client their token was minted with. */
    private val clientSecret: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchangeCode(code: String, challenge: PkceChallenge, redirectUri: String): DriveGrant =
        post(tokenEndpoint, GoogleOAuth.codeExchangeForm(clientId, code, challenge, redirectUri, clientSecret))

    /** §23: `invalid_grant` here is `AUTH_REQUIRED` — the 7-day Testing expiry among others. */
    suspend fun refresh(refreshToken: String): DriveGrant =
        post(tokenEndpoint, GoogleOAuth.refreshForm(clientId, refreshToken, clientSecret))

    /**
     * §8.3: revoking a refresh token ends the whole grant.
     *
     * A token Google no longer recognises answers 400; it is already revoked,
     * which is the outcome being asked for, so that is not an error.
     */
    suspend fun revoke(token: String): Unit = withContext(io) {
        val request = Request.Builder()
            .url(revokeEndpoint)
            .post(Pkce.formBody(GoogleOAuth.revokeForm(token)).toRequestBody(FORM))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 400) return@use
            throw DriveErrors.toException(response.code, response.body?.string().orEmpty(), DriveErrors.Surface.TOKEN)
        }
    }

    private suspend fun post(url: String, form: Map<String, String>): DriveGrant = withContext(io) {
        val request = Request.Builder().url(url).post(Pkce.formBody(form).toRequestBody(FORM)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveErrors.toException(response.code, text, DriveErrors.Surface.TOKEN)
            parse(text)
        }
    }

    private fun parse(body: String): DriveGrant {
        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            // Not the body: it is a token response (§26).
            throw CloudException(CloudErrorKind.PERMANENT, "Google returned a token response that is not JSON")
        }
        fun string(name: String) = (fields[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return DriveGrant(
            accessToken = string("access_token")
                ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Google returned no access_token"),
            refreshToken = string("refresh_token"),
            // Google sends 3599. A missing value is treated as already expiring,
            // so the next call refreshes rather than trusting a token blind.
            expiresIn = (string("expires_in")?.toLongOrNull() ?: 0L).coerceAtLeast(0L).seconds,
            grantedScopes = GoogleOAuth.parseScopes(string("scope")),
        )
    }

    private companion object {
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}

/** Supplies the credentials a [GoogleDriveCloudProvider] call needs (spec §8.3). */
interface DriveTokenSource {

    /** A currently valid access token; refreshed, never cached indefinitely. */
    suspend fun accessToken(account: AccountId): String

    /** §7: what this account's user actually granted. */
    suspend fun grantedScopes(account: AccountId): Set<String>

    /**
     * The access token of a grant adopted but not yet filed under an account.
     * Only [GoogleDriveCloudProvider.authenticate] uses it, to ask who the user is.
     */
    suspend fun pendingAccessToken(): String

    /** Files the pending grant under [account], now that `about.get` has named it. */
    suspend fun bindPending(account: AccountId)

    /** §8.3: revoke the account's grant at Google. The stored credential is the caller's to clear. */
    suspend fun revoke(account: AccountId)
}

/**
 * §8.3 for Drive: a refresh token in Keystore-backed storage, an access token
 * only in memory, keyed by [AccountId] from the start (the v0.4 gap Dropbox
 * closed late).
 *
 * Refreshes are serialized through one mutex, for the reason
 * `StoredDropboxTokenSource` gives: a resumed transfer would otherwise refresh
 * once per item in flight.
 */
class StoredDriveTokenSource(
    private val tokens: DriveTokenClient,
    private val store: RefreshTokenStore,
    private val scopes: suspend (AccountId) -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val skew: Duration = 5.minutes,
) : DriveTokenSource {

    private val mutex = Mutex()
    private val cached = mutableMapOf<AccountId, Cached>()
    private val granted = mutableMapOf<AccountId, Set<String>>()
    private var pending: DriveGrant? = null

    private data class Cached(val value: String, val expiresAtMillis: Long)

    /**
     * Takes over a grant that has just been completed (§8.1).
     *
     * Held in memory only until [bindPending]: nothing is written to the store
     * until the grant has an account to be filed under, so an abandoned connect
     * leaves no credential behind (§8.3).
     */
    suspend fun adopt(grant: DriveGrant) = mutex.withLock { pending = grant }

    override suspend fun pendingAccessToken(): String = mutex.withLock {
        checkNotNull(pending) { "no Google grant is waiting to be filed" }.accessToken
    }

    override suspend fun bindPending(account: AccountId) = mutex.withLock {
        val grant = checkNotNull(pending) { "no Google grant is waiting to be filed" }
        val refresh = grant.refreshToken ?: throw CloudException(
            CloudErrorKind.AUTH_REQUIRED,
            "Google issued no refresh token; the sign-in cannot be kept",
        )
        store.write(account, refresh)
        cached[account] = Cached(grant.accessToken, expiry(grant))
        if (grant.grantedScopes.isNotEmpty()) granted[account] = grant.grantedScopes
        pending = null
    }

    /** Forgets a grant that will never be filed: a connect that failed after the exchange. */
    suspend fun discardPending() = mutex.withLock { pending = null }

    /** The scopes of the pending grant, for the account row being written. */
    suspend fun pendingScopes(): Set<String> = mutex.withLock { pending?.grantedScopes.orEmpty() }

    override suspend fun accessToken(account: AccountId): String = mutex.withLock {
        cached[account]?.takeIf { now() < it.expiresAtMillis }?.let { return@withLock it.value }
        val refreshToken = store.read(account)
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "this Google Drive account is not connected")
        val grant = tokens.refresh(refreshToken)
        // Only when Google sent one; writing a refresh response's null over the
        // stored token would disconnect the account while keeping it alive.
        grant.refreshToken?.let { store.write(account, it) }
        cached[account] = Cached(grant.accessToken, expiry(grant))
        grant.accessToken
    }

    override suspend fun grantedScopes(account: AccountId): Set<String> =
        granted[account]?.takeIf { it.isNotEmpty() } ?: scopes(account)

    override suspend fun revoke(account: AccountId) {
        val refreshToken = mutex.withLock {
            cached.remove(account)
            store.read(account)
        } ?: return
        tokens.revoke(refreshToken)
    }

    private fun expiry(grant: DriveGrant): Long =
        now() + (grant.expiresIn - skew).coerceAtLeast(Duration.ZERO).inWholeMilliseconds
}
