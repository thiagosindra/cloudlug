package dev.thiagosindra.cloudlug.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.RefreshTokenStore
import dev.thiagosindra.cloudlug.provider.googledrive.DriveTokenClient
import dev.thiagosindra.cloudlug.provider.googledrive.GoogleOAuth
import dev.thiagosindra.cloudlug.provider.googledrive.StoredDriveTokenSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.security.SecureRandom

/**
 * §8.1 and §8.4 for Google Drive: a Custom Tab, the reverse-client-id
 * redirect, and a code exchanged with PKCE and no secret.
 *
 * The same shape as [DropboxConnector], deliberately: AppAuth drives only the
 * browser leg, and the PKCE values, the authorize parameters and the exchange
 * are CloudLug's own — the code `tools/drive-auth` has already run against
 * Google by hand. Google rejects sign-in from embedded WebViews, so a Custom
 * Tab is not only §8.4's choice but the only one that works.
 *
 * Google's grant names no account, so [complete] hands the grant to
 * [StoredDriveTokenSource] as *pending* and lets the adapter's
 * `authenticate()` ask `about.get` who the user is before anything is stored.
 */
class GoogleDriveConnector(
    context: Context,
    private val drive: CloudProvider,
    private val tokens: StoredDriveTokenSource,
    private val credentials: RefreshTokenStore,
    private val pending: PendingAuthorization,
    private val tokenClient: DriveTokenClient,
    private val random: SecureRandom = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccountConnector {

    override val provider: ProviderType get() = ProviderType.GOOGLE_DRIVE

    private val appContext = context.applicationContext

    private val configuration = AuthorizationServiceConfiguration(
        Uri.parse(GoogleOAuth.AUTHORIZE_ENDPOINT),
        Uri.parse(GoogleOAuth.TOKEN_ENDPOINT),
    )

    private val service: AuthorizationService by lazy { AuthorizationService(appContext) }

    override fun authorizationIntent(): Intent {
        val challenge = Pkce.newChallenge(random)
        val request = AuthorizationRequest.Builder(
            configuration,
            GoogleOAuth.ANDROID_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(GoogleOAuth.ANDROID_REDIRECT_URI),
        )
            .setCodeVerifier(challenge.verifier, challenge.challenge, challenge.method)
            .setState(challenge.state)
            .setScopes(GoogleOAuth.SCOPES)
            // §8.3: Google's equivalent of Dropbox's token_access_type=offline.
            // Without prompt=consent a user who connected before gets no
            // refresh token, and the account works for an hour.
            .setPrompt("consent")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()

        // Built first, remembered second, as for Dropbox: a verifier stored
        // for an attempt that could not open would be a credential kept for
        // nothing (§8.3).
        val intent = try {
            service.getAuthorizationRequestIntent(request)
        } catch (noBrowser: ActivityNotFoundException) {
            throw NoBrowserAvailableException()
        }
        pending.remember(challenge)
        return intent
    }

    override suspend fun complete(result: Intent?): CloudAccount = withContext(io) {
        if (result == null) {
            pending.discard()
            throw AuthorizationCancelledException()
        }

        val failure = AuthorizationException.fromIntent(result)
        val response = AuthorizationResponse.fromIntent(result)
        val challenge = pending.take()

        if (failure != null) {
            if (failure.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
                throw AuthorizationCancelledException()
            }
            // Not failure.message: provider prose stays out of anything that
            // might be logged (§26).
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Google declined the sign-in")
        }
        if (response == null) throw AuthorizationCancelledException()
        if (challenge == null) {
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "no sign-in was in progress")
        }

        // §8.4: the redirect is ours only if it carries the state we sent.
        if (!Pkce.statesMatch(challenge.state, response.state)) {
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "the sign-in response did not match this request")
        }

        val code = response.authorizationCode
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Google returned no authorization code")

        val grant = tokenClient.exchangeCode(code, challenge, GoogleOAuth.ANDROID_REDIRECT_URI)
        if (grant.refreshToken == null) {
            throw CloudException(
                CloudErrorKind.AUTH_REQUIRED,
                "Google issued no refresh token; the sign-in cannot be kept",
            )
        }

        tokens.adopt(grant)
        try {
            drive.authenticate()
        } catch (e: Exception) {
            // The grant never reached the store; make sure it does not linger
            // in memory either, waiting to be filed under the next account.
            tokens.discardPending()
            throw e
        }
    }

    /** §8.3: revoke at Google first, then forget, so a failed revoke leaves something to retry with. */
    override suspend fun disconnect(account: AccountId) = withContext(io) {
        drive.disconnect(account)
        credentials.clear(account)
        pending.discard()
    }

    override fun rolesFor(grantedScopes: Set<String>) = GoogleOAuth.rolesFor(grantedScopes)

    fun close() = service.dispose()
}
