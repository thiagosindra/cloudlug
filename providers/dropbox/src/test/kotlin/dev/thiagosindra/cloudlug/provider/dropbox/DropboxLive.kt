package dev.thiagosindra.cloudlug.provider.dropbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The gate every live test goes through (spec §31.2).
 *
 * Live tests touch a real account, so they are opt-in: without
 * `DROPBOX_REFRESH_TOKEN` they are skipped, not failed, and CI stays hermetic.
 * A fork with no secret goes green.
 *
 * ### Why a refresh token and not an access token
 *
 * A Dropbox access token lives about four hours, so a stored one is broken
 * before anyone notices it was ever working — the run fails with an auth error
 * that looks like a bug in the adapter. The refresh token is long-lived and
 * each run mints its own access token, which is also the §8.3 path the app
 * itself uses, so the live tests exercise it rather than routing around it.
 *
 * ### The destructive-scope guard
 *
 * The contract suite creates, renames and deletes. Everything it touches must
 * be under [root], and this refuses to hand out credentials at all if [root] is
 * missing, blank, `/`, or not an absolute path. A suite that can address the
 * account root has no business running against anyone's Dropbox, scratch or
 * not, and the check is here rather than in each test because one forgotten
 * prefix is all it would take.
 */
object DropboxLive {

    private const val REFRESH_TOKEN_VARIABLE = "DROPBOX_REFRESH_TOKEN"
    private const val ROOT_VARIABLE = "DROPBOX_TEST_ROOT"

    private val json = Json { ignoreUnknownKeys = true }

    val refreshToken: String? = System.getenv(REFRESH_TOKEN_VARIABLE)?.takeIf { it.isNotBlank() }

    /** Where the suite is allowed to write. Everything it creates goes under this. */
    val root: String = System.getenv(ROOT_VARIABLE)?.trim().orEmpty().ifBlank { "/cloudlug-contract-tests" }

    val isConfigured: Boolean get() = refreshToken != null

    /**
     * Skips the calling test when the account is not configured, and fails it
     * outright when the configuration is dangerous.
     *
     * The difference matters: an unset token is an ordinary "not today", while
     * a root of `/` is a loaded gun and must never be quietly tolerated.
     */
    fun assumeAvailable() {
        requireSafeRoot()
        assumeTrue(
            isConfigured,
            "$REFRESH_TOKEN_VARIABLE is not set, so the live Dropbox contract tests are skipped.",
        )
    }

    fun requireSafeRoot() {
        check(root.startsWith("/")) { "$ROOT_VARIABLE must be an absolute Dropbox path, got '$root'" }
        check(root.trimEnd('/').length > 1) {
            "$ROOT_VARIABLE is '$root', which is the account root. These tests delete what they create; " +
                "point them at a folder of their own, e.g. /cloudlug-contract-tests."
        }
    }

    /** A path inside [root], and the only way a live test should name anything. */
    fun path(relative: String): String = "${root.trimEnd('/')}/${relative.trimStart('/')}"

    /**
     * Exchanges the refresh token for an access token (§8.3).
     *
     * Uses the same [DropboxOAuth.refreshForm] the app uses, so a change that
     * breaks refresh breaks here too rather than only on a user's phone.
     */
    fun accessToken(client: OkHttpClient = OkHttpClient()): String {
        val token = checkNotNull(refreshToken) { "no $REFRESH_TOKEN_VARIABLE; call assumeAvailable() first" }
        val form = FormBody.Builder().apply {
            DropboxOAuth.refreshForm(token).forEach { (key, value) -> add(key, value) }
        }.build()

        val request = Request.Builder().url(DropboxOAuth.TOKEN_ENDPOINT).post(form).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                // The body of a failed refresh carries an error tag, never a
                // token, but it is still not echoed wholesale (§26).
                val tag = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }
                    .getOrNull()
                "Refreshing the access token failed: HTTP ${response.code}${tag?.let { " ($it)" } ?: ""}. " +
                    "If this says invalid_grant the stored $REFRESH_TOKEN_VARIABLE has been revoked; " +
                    "mint a new one with :tools:dropbox-auth:dropboxAuth."
            }
            return checkNotNull(
                json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.content,
            ) { "the refresh response carried no access_token" }
        }
    }
}
