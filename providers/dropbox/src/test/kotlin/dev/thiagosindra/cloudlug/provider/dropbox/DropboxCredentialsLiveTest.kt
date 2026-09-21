package dev.thiagosindra.cloudlug.provider.dropbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the stored refresh token still works, and nothing else.
 *
 * This is deliberately the smallest possible live test. It exists so that when
 * the contract suite fails, the first question — "is the credential even good?"
 * — has already been answered by a test that touches no files, creates nothing
 * and deletes nothing. A credential problem and an adapter problem look alike
 * from a stack trace, and separating them early is worth one extra test.
 *
 * Skipped entirely when `DROPBOX_REFRESH_TOKEN` is unset, so CI stays hermetic.
 */
class DropboxCredentialsLiveTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the stored refresh token mints a working access token`() {
        DropboxLive.assumeAvailable()

        val client = OkHttpClient()
        val accessToken = DropboxLive.accessToken(client)

        assertTrue(accessToken.isNotBlank(), "the refresh exchange returned a blank access token")
        assertFalse(
            accessToken == DropboxLive.refreshToken,
            "the access token is the refresh token — the exchange did not happen",
        )

        // get_current_account takes no arguments: an empty body and no
        // Content-Type, which is Dropbox's convention for argument-less routes.
        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/users/get_current_account")
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(
                response.isSuccessful,
                "get_current_account failed: HTTP ${response.code}. " +
                    "A 401 here means the scopes were not granted or the token was revoked.",
            )

            val account = json.parseToJsonElement(body).jsonObject
            assertTrue(
                account["account_id"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "the account carried no account_id",
            )
        }
    }

    @Test
    fun `the configured test root is a folder of its own`() {
        // Not a live call: it runs even without a token, because a dangerous
        // root should be caught before anyone wires up a credential.
        DropboxLive.requireSafeRoot()
    }
}
