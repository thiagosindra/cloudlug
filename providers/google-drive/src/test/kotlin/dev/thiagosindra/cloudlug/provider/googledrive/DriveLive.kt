package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.tools.drive.DriveTooling
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The gate every live Drive test goes through (spec §31.2), as `DropboxLive`
 * is for Dropbox.
 *
 * Opt-in: without `DRIVE_REFRESH_TOKEN` (and the tooling secret it was minted
 * with) every live test is skipped, so CI and forks stay hermetic. With them,
 * it refuses outright unless `DRIVE_TEST_ROOT` names a folder — never `root`,
 * the whole of My Drive — because the suite creates and deletes.
 *
 * The token was minted by `tools/drive-auth` with the Desktop tooling client,
 * so refreshing it needs that client and its secret. That is the one way this
 * differs from the app's path, which uses the Android client and no secret;
 * the refresh logic itself is the app's own [DriveTokenClient].
 */
object DriveLive {

    private fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    val refreshToken: String? = env("DRIVE_REFRESH_TOKEN")
    val clientSecret: String? = env("DRIVE_TOOL_CLIENT_SECRET")
    val root: String? = env("DRIVE_TEST_ROOT")

    val isConfigured: Boolean get() = refreshToken != null && clientSecret != null

    fun assumeAvailable() {
        assumeTrue(isConfigured, "DRIVE_REFRESH_TOKEN is not set, so the live Drive contract tests are skipped.")
        requireSafeRoot(root)
    }

    /** A loaded gun is not a skip: a configured run with no safe root fails. */
    fun requireSafeRoot(root: String?) {
        checkNotNull(root) { "DRIVE_TEST_ROOT is not set. :tools:drive-auth:driveAuth prints it." }
        check(!root.equals(DriveObjects.ROOT, ignoreCase = true)) {
            "DRIVE_TEST_ROOT is 'root', the whole of My Drive. These tests delete what they create; point them at the test folder."
        }
    }

    /** §8.3's refresh, with the tooling client the token belongs to. */
    class Tokens(client: OkHttpClient) : DriveTokenSource {
        private val tokenClient = DriveTokenClient(client, clientId = DriveTooling.DESKTOP_CLIENT_ID, clientSecret = clientSecret)
        private var token: String? = null

        override suspend fun accessToken(account: AccountId): String =
            token ?: tokenClient.refresh(checkNotNull(refreshToken)).accessToken.also { token = it }

        override suspend fun grantedScopes(account: AccountId) = setOf(GoogleOAuth.SCOPE_FILE)

        // One credential, one account: authenticate() asks about.get with it.
        override suspend fun pendingAccessToken(): String = accessToken(AccountId("pending"))
        override suspend fun bindPending(account: AccountId) = Unit

        // Never revoke the repository secret from a test.
        override suspend fun revoke(account: AccountId) = Unit
    }
}
