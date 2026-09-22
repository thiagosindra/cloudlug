package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Where the long-lived refresh token lives, as this module needs to see it.
 *
 * Deliberately three methods and no Android types: §8.3's real store is
 * Keystore-backed and only exists on a device, and putting that type in this
 * signature would make the refresh logic — the part with the four-hour failure
 * mode — testable only on an emulator.
 *
 * Implementations must not return a token they cannot decrypt; §8.3 treats an
 * unreadable credential as absent, so [read] answering null is the recovery.
 */
interface DropboxRefreshTokenStore {
    fun read(account: AccountId): String?
    fun write(account: AccountId, token: String)
    fun clear(account: AccountId)
}

/**
 * §8.3's credential handling: a stored refresh token, and an access token that
 * exists only in memory.
 *
 * The access token is never written anywhere. It lives about four hours, and a
 * token that leaks with the process dies with the process — which is the whole
 * reason §8.3 separates the two.
 *
 * Refreshing is serialized through a mutex. Without one, a transfer that
 * resumes with several items in flight sends a burst of simultaneous refreshes
 * at the moment the token expires: Dropbox answers each of them, every answer
 * but the last is thrown away, and the account has spent its rate limit
 * discovering the same token repeatedly.
 *
 * ### One instance, many accounts
 *
 * v0.4 made this per-account. A Dropbox-to-Dropbox transfer has two of the
 * same provider's accounts in flight at once — reading from one and writing to
 * the other — so a single cached token, or a single stored credential key,
 * would have had the destination's token answering the source's requests.
 *
 * The mutex stays one for the instance rather than one per account. Refreshes
 * are brief and rare, contention between two accounts is not worth a second
 * lock, and one lock is one thing to reason about.
 */
class StoredDropboxTokenSource(
    private val tokens: DropboxTokenClient,
    private val store: DropboxRefreshTokenStore,
    private val scopes: suspend (AccountId) -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val skew: Duration = DEFAULT_SKEW,
) : DropboxTokenSource {

    private val mutex = Mutex()
    private val cached = mutableMapOf<AccountId, CachedToken>()
    private val grantedScopes = mutableMapOf<AccountId, Set<String>>()

    /** An access token and when this source stops trusting it. */
    private data class CachedToken(val value: String, val expiresAtMillis: Long)

    /**
     * The account whose grant was adopted most recently (§8.1).
     *
     * [DropboxCloudProvider.authenticate] is the call that *discovers* an
     * account id, so it has none to pass; this is how it knows whose token to
     * send. Dropbox puts `account_id` in the token response, so the answer is
     * known before the call that asks who the user is.
     */
    private var lastAdopted: AccountId? = null

    /**
     * Takes over a grant that has just been completed (§8.1).
     *
     * Without this, connecting an account would store the refresh token and
     * then immediately spend it on a refresh for an access token Dropbox had
     * already handed over in the same response — a wasted round trip at the
     * exact moment the user is watching a spinner.
     *
     * It also carries the granted scopes, which §7 needs and which arrive only
     * here: there is no endpoint that reports them afterwards, and the account
     * row they belong in has not been written yet.
     */
    suspend fun adopt(grant: DropboxGrant) = mutex.withLock {
        val account = AccountId(
            requireNotNull(grant.accountId) {
                "Dropbox returned no account_id, so this grant cannot be filed against an account"
            },
        )

        grant.refreshToken?.let { store.write(account, it) }
        cached[account] = CachedToken(
            value = grant.accessToken,
            expiresAtMillis = now() + (grant.expiresIn - skew).coerceAtLeast(Duration.ZERO).inWholeMilliseconds,
        )
        if (grant.grantedScopes.isNotEmpty()) grantedScopes[account] = grant.grantedScopes
        lastAdopted = account
    }

    override suspend fun accessToken(account: AccountId): String = mutex.withLock {
        cached[account]?.takeIf { now() < it.expiresAtMillis }?.let { return@withLock it.value }

        val refreshToken = store.read(account)
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "this Dropbox account is not connected")

        val grant = tokens.refresh(refreshToken)

        // Only when Dropbox sent one. A refresh response carries no
        // refresh_token, and writing that null over the stored token would
        // disconnect the account at the moment it was being kept alive.
        grant.refreshToken?.let { store.write(account, it) }

        cached[account] = CachedToken(
            value = grant.accessToken,
            expiresAtMillis = now() + (grant.expiresIn - skew).coerceAtLeast(Duration.ZERO).inWholeMilliseconds,
        )
        grant.accessToken
    }

    /**
     * §7's granted scopes.
     *
     * The grant knows them first — during [adopt] the account row does not
     * exist yet — and the account record knows them on every later launch.
     */
    override suspend fun grantedScopes(account: AccountId): Set<String> =
        grantedScopes[account]?.takeIf { it.isNotEmpty() } ?: scopes(account)

    /** See [lastAdopted]. */
    override suspend fun accountJustConnected(): AccountId = mutex.withLock {
        checkNotNull(lastAdopted) { "no Dropbox grant has been adopted in this process" }
    }

    companion object {
        /**
         * Refresh this far before expiry.
         *
         * A token that expires mid-upload fails a transfer that may have been
         * running unattended for an hour; refreshing early costs one request.
         * The asymmetry is the whole argument.
         */
        val DEFAULT_SKEW = 5.minutes
    }
}
