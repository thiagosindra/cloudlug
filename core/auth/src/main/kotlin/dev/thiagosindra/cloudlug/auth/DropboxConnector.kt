package dev.thiagosindra.cloudlug.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxOAuth
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxTokenClient
import dev.thiagosindra.cloudlug.provider.dropbox.StoredDropboxTokenSource
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.security.SecureRandom

/**
 * §8.1 for Dropbox: a Custom Tab, a redirect, and a code exchanged with PKCE.
 *
 * AppAuth drives only the browser leg — opening the tab, catching the redirect
 * on `dev.thiagosindra.cloudlug://oauth/dropbox`, and surviving the trip
 * through another app. The PKCE values, the authorize parameters and the token
 * exchange are CloudLug's own, so the verifier logic running in the app is the
 * logic `tools/dropbox-auth` has already run by hand against the real Dropbox.
 * Two implementations of PKCE, one of them exercised only in production, is how
 * a subtle encoding bug survives to ship.
 *
 * There is no client secret. CloudLug is a public client: anything shipped in
 * the APK is readable, so a secret there is not a secret, which is the reason
 * §8.1 requires PKCE in the first place.
 */
class DropboxConnector(
    context: Context,
    private val dropbox: CloudProvider,
    private val tokens: StoredDropboxTokenSource,
    private val credentials: KeystoreRefreshTokens,
    private val pending: PendingAuthorization,
    private val tokenClient: DropboxTokenClient,
    private val random: SecureRandom = SecureRandom(),
) : AccountConnector {

    override val provider: ProviderType get() = ProviderType.DROPBOX

    private val appContext = context.applicationContext

    private val configuration = AuthorizationServiceConfiguration(
        Uri.parse(DropboxOAuth.AUTHORIZE_ENDPOINT),
        Uri.parse(DropboxOAuth.TOKEN_ENDPOINT),
    )

    private val service: AuthorizationService by lazy { AuthorizationService(appContext) }

    override fun authorizationIntent(): Intent {
        val challenge = DropboxOAuth.newChallenge(random)
        pending.remember(challenge)

        val request = AuthorizationRequest.Builder(
            configuration,
            DropboxOAuth.APP_KEY,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setCodeVerifier(challenge.verifier, challenge.challenge, challenge.method)
            .setState(challenge.state)
            .setScopes(DropboxOAuth.SCOPES)
            // §8.3. Without it Dropbox issues no refresh token and the account
            // stops working about four hours later, with nothing to show why.
            .setAdditionalParameters(mapOf("token_access_type" to "offline"))
            .build()

        return service.getAuthorizationRequestIntent(request)
    }

    override suspend fun complete(result: Intent?): CloudAccount {
        if (result == null) {
            pending.discard()
            throw AuthorizationCancelledException()
        }

        val failure = AuthorizationException.fromIntent(result)
        val response = AuthorizationResponse.fromIntent(result)

        // The attempt is consumed whatever happens next: a verifier is good for
        // one exchange, and a spent one left behind is a credential kept past
        // its purpose.
        val challenge = pending.take()

        if (failure != null) {
            if (failure.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
                throw AuthorizationCancelledException()
            }
            // Not failure.getMessage(): it is written by the provider and §26
            // keeps provider text out of anything that might be logged.
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Dropbox declined the sign-in")
        }

        if (response == null) throw AuthorizationCancelledException()

        if (challenge == null) {
            // A redirect arrived with nothing in progress. Either the app was
            // reinstalled mid-flow or something else sent it; §8.4 says the
            // code in it is not ours to spend.
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "no sign-in was in progress")
        }

        // §8.4, done here rather than trusted to the library: a redirect is
        // only ours if it carries the state this attempt sent. The comparison
        // is constant-time, which costs nothing and settles the question.
        if (!DropboxOAuth.statesMatch(challenge.state, response.state)) {
            throw CloudException(CloudErrorKind.AUTH_REQUIRED, "the sign-in response did not match this request")
        }

        val code = response.authorizationCode
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "Dropbox returned no authorization code")

        val grant = tokenClient.exchangeCode(code, challenge, REDIRECT_URI)
        if (grant.refreshToken == null) {
            // Without one there is nothing to store, and the account would work
            // for four hours and then fail. Better to refuse the connection now
            // than to hand the user an account with an expiry date they cannot
            // see.
            throw CloudException(
                CloudErrorKind.AUTH_REQUIRED,
                "Dropbox issued no refresh token; the sign-in cannot be kept",
            )
        }

        tokens.adopt(grant)
        return dropbox.authenticate()
    }

    override suspend fun disconnect(account: AccountId) {
        // Revoke first (§8.3): if this fails, the credential is still here and
        // the user can try again. Clearing it first would strand a live grant
        // on their Dropbox account with nothing left that could revoke it.
        dropbox.disconnect(account)
        credentials.clear()
        pending.discard()
    }

    /**
     * §7, read off what Dropbox actually granted.
     *
     * Reading needs both scopes: `files.metadata.read` to walk a tree and
     * `files.content.read` to fetch the bytes. An account with only the first
     * can enumerate a selection it can never transfer, which is a worse
     * failure than refusing it up front.
     */
    override fun rolesFor(grantedScopes: Set<String>) = DropboxOAuth.rolesFor(grantedScopes)

    /** Releases AppAuth's browser binding. */
    fun close() = service.dispose()

    companion object {
        /**
         * Registered with Dropbox and claimed by the app's manifest. The scheme
         * is the application id, which is what keeps another app from
         * registering the same one on the same device.
         */
        const val REDIRECT_URI = "dev.thiagosindra.cloudlug://oauth/dropbox"
    }
}
