package dev.thiagosindra.cloudlug.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thiagosindra.cloudlug.auth.AccountConnector
import dev.thiagosindra.cloudlug.auth.AccountRepository
import dev.thiagosindra.cloudlug.auth.DropboxConnector
import dev.thiagosindra.cloudlug.auth.KeystoreRefreshTokens
import dev.thiagosindra.cloudlug.auth.PendingAuthorization
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.network.RedactingLogInterceptor
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxCloudProvider
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxTokenClient
import dev.thiagosindra.cloudlug.provider.dropbox.StoredDropboxTokenSource
import dev.thiagosindra.cloudlug.security.KeystoreSecretStore
import dev.thiagosindra.cloudlug.security.SecretStore
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * §8's half of the graph: the credential store, the OAuth flow, and the real
 * Dropbox adapter that depends on both.
 *
 * Separate from [DemoProvidersModule] because the two answer different
 * questions. That one exists so the engine can be driven with no account at
 * all; this one is what a connected account actually runs on.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    @Provides
    @Singleton
    fun refreshTokens(secrets: SecretStore) = KeystoreRefreshTokens(secrets)

    @Provides
    @Singleton
    fun pendingAuthorization(secrets: SecretStore) = PendingAuthorization(secrets)

    /**
     * One client for every Dropbox call, with §26's redaction installed on it.
     *
     * Installing the interceptor here rather than at each call site is the
     * point: a request cannot opt out of redaction by being written somewhere
     * that forgot about it.
     */
    @Provides
    @Singleton
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RedactingLogInterceptor())
        .build()

    @Provides
    @Singleton
    fun dropboxTokenClient(client: OkHttpClient) = DropboxTokenClient(client)

    @Provides
    @Singleton
    fun dropboxTokens(
        tokenClient: DropboxTokenClient,
        refreshTokens: KeystoreRefreshTokens,
        database: CloudLugDatabase,
    ) = StoredDropboxTokenSource(
        tokens = tokenClient,
        store = refreshTokens,
        // §7's granted scopes, read from that account's row on every launch
        // after the one that created it.
        scopes = { account -> database.accounts.findById(account)?.grantedScopes.orEmpty() },
    )

    @Provides
    @Singleton
    fun dropboxProvider(tokens: StoredDropboxTokenSource, client: OkHttpClient) =
        DropboxCloudProvider(tokens, client)

    @Provides
    @Singleton
    fun dropboxConnector(
        @ApplicationContext context: Context,
        provider: DropboxCloudProvider,
        tokens: StoredDropboxTokenSource,
        refreshTokens: KeystoreRefreshTokens,
        pending: PendingAuthorization,
        tokenClient: DropboxTokenClient,
    ) = DropboxConnector(context, provider, tokens, refreshTokens, pending, tokenClient)

    /**
     * Only Dropbox connects in v0.3.
     *
     * Google Drive is v0.4 and the demo provider needs no account at all, so
     * neither has a connector. §24.5's screen reads the absence as "this build
     * cannot connect that yet" rather than being handed a connector that
     * throws.
     */
    @Provides
    @Singleton
    fun connectors(dropbox: DropboxConnector): Map<ProviderType, @JvmSuppressWildcards AccountConnector> =
        mapOf(ProviderType.DROPBOX to dropbox)

    @Provides
    @Singleton
    fun accountRepository(
        database: CloudLugDatabase,
        connectors: Map<ProviderType, @JvmSuppressWildcards AccountConnector>,
    ) = AccountRepository(database, connectors)
}
