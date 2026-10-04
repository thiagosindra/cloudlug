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
import dev.thiagosindra.cloudlug.auth.GoogleDriveConnector
import dev.thiagosindra.cloudlug.auth.KeystoreRefreshTokens
import dev.thiagosindra.cloudlug.auth.PendingAuthorization
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.network.RedactingLogInterceptor
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxCloudProvider
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxTokenClient
import dev.thiagosindra.cloudlug.provider.dropbox.StoredDropboxTokenSource
import dev.thiagosindra.cloudlug.provider.googledrive.DriveTokenClient
import dev.thiagosindra.cloudlug.provider.googledrive.GoogleDriveCloudProvider
import dev.thiagosindra.cloudlug.provider.googledrive.StoredDriveTokenSource
import dev.thiagosindra.cloudlug.security.KeystoreSecretStore
import dev.thiagosindra.cloudlug.security.SecretStore
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/**
 * §8's half of the graph: the credential stores, the OAuth flows, and the real
 * Dropbox and Google Drive adapters that depend on them.
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

    // ---------------------------------------------------------------- Google Drive

    /** Its own key prefix, so a Drive credential can never land on a Dropbox key (§8.3). */
    @Provides
    @Singleton
    @Named("drive")
    fun driveRefreshTokens(secrets: SecretStore) = KeystoreRefreshTokens(secrets, ProviderType.GOOGLE_DRIVE)

    /** Its own attempt, so a Dropbox sign-in and a Drive sign-in cannot consume each other's verifier. */
    @Provides
    @Singleton
    @Named("drive")
    fun drivePendingAuthorization(secrets: SecretStore) = PendingAuthorization(secrets, "google-drive.pending-authorization")

    @Provides
    @Singleton
    fun driveTokenClient(client: OkHttpClient) = DriveTokenClient(client)

    @Provides
    @Singleton
    fun driveTokens(
        tokenClient: DriveTokenClient,
        @Named("drive") refreshTokens: KeystoreRefreshTokens,
        database: CloudLugDatabase,
    ) = StoredDriveTokenSource(
        tokens = tokenClient,
        store = refreshTokens,
        scopes = { account -> database.accounts.findById(account)?.grantedScopes.orEmpty() },
    )

    @Provides
    @Singleton
    fun driveProvider(tokens: StoredDriveTokenSource, client: OkHttpClient) = GoogleDriveCloudProvider(tokens, client)

    @Provides
    @Singleton
    fun driveConnector(
        @ApplicationContext context: Context,
        provider: GoogleDriveCloudProvider,
        tokens: StoredDriveTokenSource,
        @Named("drive") refreshTokens: KeystoreRefreshTokens,
        @Named("drive") pending: PendingAuthorization,
        tokenClient: DriveTokenClient,
    ) = GoogleDriveConnector(context, provider, tokens, refreshTokens, pending, tokenClient)

    /**
     * Dropbox since v0.3, Google Drive since v0.6. The demo providers need no
     * account, so they have no connector, and §24.5 leaves them out.
     */
    @Provides
    @Singleton
    fun connectors(
        dropbox: DropboxConnector,
        drive: GoogleDriveConnector,
    ): Map<ProviderType, @JvmSuppressWildcards AccountConnector> =
        mapOf(ProviderType.DROPBOX to dropbox, ProviderType.GOOGLE_DRIVE to drive)

    @Provides
    @Singleton
    fun accountRepository(
        database: CloudLugDatabase,
        connectors: Map<ProviderType, @JvmSuppressWildcards AccountConnector>,
        providers: ProviderRegistry,
    ) = AccountRepository(database, connectors, providers)
}
