package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.fake.ProviderContractTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import java.util.UUID

/**
 * §31.2's contract, against a real Dropbox account.
 *
 * The same suite the fake passes, which is the point: if Dropbox needs an
 * exception, the exception belongs in [dev.thiagosindra.cloudlug.provider.ProviderCapabilities]
 * where the engine can see it, not in a weakened test.
 *
 * Opt-in. Without `DROPBOX_REFRESH_TOKEN` every test here is skipped rather
 * than failed, so CI and forks stay hermetic — and the skip is a real skip,
 * not a test that passes having done nothing.
 *
 * ### What it is allowed to touch
 *
 * Everything, under one folder. [DropboxLive] refuses to hand out credentials
 * at all unless `DROPBOX_TEST_ROOT` is an absolute path that is not the account
 * root, and each run works inside a fresh `run-<uuid>` folder beneath it. The
 * provider handed to the suite is wrapped so that even `rootOf(account)` — the
 * one call that means "the top of this account" — answers with the run folder.
 * The suite creates, renames, uploads and deletes; a suite that could address
 * the account root has no business running against anyone's Dropbox.
 */
class DropboxContractLiveTest : ProviderContractTest() {

    override fun newProvider(): CloudProvider {
        DropboxLive.assumeAvailable()

        val provider = DropboxCloudProvider(LiveTokens, client)
        val folder = "run-${UUID.randomUUID()}"
        val runRoot = runBlocking {
            provider.prepareDestination(
                account = account(provider),
                parent = DropboxObjects.idOf(DropboxLive.root),
                relativePath = CloudPath.of(folder),
            ).id
        }
        createdRuns += DropboxLive.path(folder)

        // rootOf() would otherwise be the Dropbox account root, and one of the
        // inherited tests seeds directly under it.
        return ConfinedToRun(provider, runRoot)
    }

    override fun account(provider: CloudProvider): AccountId = accountId

    override suspend fun rootFolder(provider: CloudProvider): CloudObjectId = provider.rootOf(account(provider))

    override suspend fun seedFolder(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
    ): CloudObjectId = provider.prepareDestination(account(provider), parent, CloudPath.of(name)).id

    override suspend fun seedFile(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
        content: ByteArray,
    ): CloudObjectId {
        val session = provider.beginUpload(
            account(provider),
            UploadRequest(
                account = account(provider),
                parent = parent,
                name = name,
                size = content.size.toLong(),
                mimeType = null,
            ),
        )
        provider.uploadChunk(session, Chunk(offset = 0, bytes = content, isFinal = true))
        return provider.finishUpload(session).id
    }

    /**
     * Everything the suite is given, confined to one run's folder.
     *
     * Delegation rather than a subclass: every method but [rootOf] must be the
     * real adapter's, or the suite would be testing the wrapper.
     */
    private class ConfinedToRun(
        delegate: CloudProvider,
        private val runRoot: CloudObjectId,
    ) : CloudProvider by delegate {
        override fun rootOf(account: AccountId): CloudObjectId = runRoot
    }

    /**
     * The §8.3 path the app itself uses: a long-lived refresh token exchanged
     * for a short-lived access token. A stored access token would be dead
     * within four hours and the failure would read as an adapter bug.
     */
    private object LiveTokens : DropboxTokenSource {
        private val token: String by lazy { DropboxLive.accessToken(client) }
        override suspend fun accessToken(account: AccountId): String = token
        override suspend fun grantedScopes(account: AccountId): Set<String> = DropboxOAuth.SCOPES.toSet()

        // One refresh token, one account — the live suite has no second one to
        // confuse this with, which is why authenticate() can be answered from
        // the same credential every other call uses.
        override suspend fun accountJustConnected(): AccountId = accountId
    }

    companion object {
        private val client = OkHttpClient()
        private val createdRuns = mutableListOf<String>()

        private val accountId: AccountId by lazy {
            runBlocking { DropboxCloudProvider(LiveTokens, client).authenticate().id }
        }

        /**
         * Removes every folder this class created.
         *
         * Each test makes its own run folder, so a failed run leaves one behind
         * and the next `DROPBOX_TEST_ROOT` fills up quietly. Failures here are
         * swallowed deliberately: a cleanup that fails the build hides the
         * test failure that caused it.
         */
        @JvmStatic
        @AfterAll
        fun removeWhatTheRunCreated() {
            if (!DropboxLive.isConfigured) return
            val token = runCatching { DropboxLive.accessToken(client) }.getOrNull() ?: return

            createdRuns.forEach { path ->
                val body = buildJsonObject { put("path", path) }.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${DropboxApi.API_HOST}/2/files/delete_v2")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                runCatching { client.newCall(request).execute().close() }
            }
            createdRuns.clear()
        }
    }
}
