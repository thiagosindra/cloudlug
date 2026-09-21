package dev.thiagosindra.cloudlug.auth

import android.content.Intent
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount

/**
 * One provider's §8.1 connect flow, as the accounts screen needs to see it.
 *
 * The screen must not know that Dropbox uses PKCE, that the redirect is a
 * custom scheme, or that the token endpoint speaks a different dialect from the
 * API. It launches an [Intent] and hands back whatever comes out, which is the
 * same shape Google Drive will need in v0.4.
 */
interface AccountConnector {

    val provider: ProviderType

    /**
     * The intent that opens the provider's consent page (§8.1).
     *
     * §8.1 requires a Custom Tab rather than a WebView: a WebView would let
     * this app see the user's password as they type it into their cloud
     * provider, which is exactly the thing OAuth exists to avoid.
     *
     * Calling this starts an attempt and remembers its PKCE material, so an
     * abandoned attempt is replaced by the next one rather than accumulating.
     */
    fun authorizationIntent(): Intent

    /**
     * Finishes the flow from the redirect (§8.1, §8.4).
     *
     * [result] is the intent the authorization activity returned — null when
     * the user backed out of the browser, which is an ordinary cancellation
     * and not an error worth a §24 failure dialog.
     */
    suspend fun complete(result: Intent?): CloudAccount

    /**
     * §8.3: revoke the grant at the provider, then forget the credential.
     *
     * In that order. Forgetting first would leave a live grant on the user's
     * cloud account with no way left to revoke it, which is the opposite of
     * what someone pressing Disconnect is asking for.
     */
    suspend fun disconnect(account: AccountId)

    /**
     * §7: what these granted scopes let the account do.
     *
     * Only the provider can answer. §7 makes the *granted* scopes decide
     * whether an account may act as source, destination or both, and a user who
     * declines a scope at the consent screen has an account that is real,
     * usable, and not usable for everything — a state the UI has to be able to
     * describe rather than discover mid-transfer.
     */
    fun rolesFor(grantedScopes: Set<String>): AccountRoles
}


/** The user backed out of the consent page. Not a failure (§24). */
class AuthorizationCancelledException : Exception("the sign-in was cancelled")
