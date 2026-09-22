package dev.thiagosindra.cloudlug.auth

import android.content.Intent
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.entity.AccountEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Connected accounts, as §24.5's screen and §12.4's storage see them.
 *
 * Two stores, deliberately: non-secret metadata in Room so the accounts list
 * renders without touching Keystore, and the credential in §8.3's store, which
 * this class never reads. Nothing here can log a token because nothing here
 * holds one.
 */
class AccountRepository(
    private val database: CloudLugDatabase,
    private val connectors: Map<ProviderType, AccountConnector>,
    private val providers: ProviderRegistry,
    private val clock: () -> Instant = Instant::now,
) {

    fun observe(): Flow<List<CloudAccount>> =
        database.accounts.observeAll().map { rows -> rows.map(AccountEntity::asCloudAccount) }

    suspend fun connected(provider: ProviderType): CloudAccount? =
        database.accounts.listAll().firstOrNull { it.provider == provider }?.asCloudAccount()

    /** §7, for a provider that may not be connected at all. */
    suspend fun grantedScopes(provider: ProviderType): Set<String> =
        connected(provider)?.grantedScopes.orEmpty()

    /**
     * Whether this build can connect [provider] at all.
     *
     * A provider with no connector is one this version does not support yet —
     * Google Drive until v0.4. §24.5's screen shows that as a fact rather than
     * offering a Connect button that cannot work.
     */
    fun canConnect(provider: ProviderType): Boolean = provider in connectors

    /**
     * §7: what this account may do.
     *
     * The connector decides, because interpreting a provider's scope strings
     * is provider knowledge. Where there is no connector there is also no
     * grant to interpret — nothing in this build performed the sign-in — so
     * the answer falls back to what the adapter declares the build can do at
     * all (§5's `canBeSource`/`canBeDestination`).
     *
     * That fallback does not weaken §7 where it matters: a provider CloudLug
     * can actually sign in to has a connector, and there the grant decides.
     */
    fun rolesFor(account: CloudAccount): AccountRoles =
        connectors[account.provider]?.rolesFor(account.grantedScopes)
            ?: providers.provider(account.provider).capabilities.let {
                AccountRoles(canBeSource = it.canBeSource, canBeDestination = it.canBeDestination)
            }

    fun authorizationIntent(provider: ProviderType): Intent = connector(provider).authorizationIntent()

    /**
     * Records a completed grant (§8.1 → §12.4).
     *
     * The credential is already stored by the time this returns — the connector
     * put it there so it could ask the provider who the user is. What lands
     * here is only the metadata the accounts list draws.
     */
    suspend fun completeConnection(provider: ProviderType, result: Intent?): CloudAccount {
        val account = connector(provider).complete(result)
        database.accounts.upsert(
            AccountEntity(
                id = account.id,
                provider = account.provider,
                providerAccountId = account.id.value,
                displayName = account.displayName,
                displayEmail = account.displayEmail,
                grantedScopes = account.grantedScopes,
                createdAt = clock(),
            ),
        )
        return account
    }

    /**
     * §8.3's disconnect: revoke at the provider, then forget locally.
     *
     * If the revoke fails the row stays, which is the right way round — an
     * account still listed can be disconnected again, whereas a forgotten one
     * leaves a live grant on the user's cloud account that this app can no
     * longer revoke (ADR-0028).
     */
    suspend fun disconnect(account: AccountId) {
        val row = database.accounts.findById(account) ?: return
        // No connector means nothing in this build signed in, so there is no
        // grant to revoke — a demo row, or a provider a later build stopped
        // supporting. Forgetting it locally is the whole of the work.
        //
        // Not `connector(...)`: that throws IllegalStateException, which is
        // not a CloudException and so falls straight past §24.5's error
        // handling and out of the ViewModel's coroutine. Pressing Disconnect
        // on such a row crashed the app.
        connectors[row.provider]?.disconnect(account)
        database.accounts.delete(account)
    }

    private fun connector(provider: ProviderType): AccountConnector =
        connectors[provider] ?: error("no connector for $provider")
}

private fun AccountEntity.asCloudAccount() = CloudAccount(
    id = id,
    provider = provider,
    displayName = displayName,
    displayEmail = displayEmail,
    grantedScopes = grantedScopes,
)
