package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.fake.ProviderContractTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * §31.2's contract against a real Google Drive account — the same suite the
 * fake and Dropbox pass, unweakened. Drive is a destination in this build, so
 * the suite's own `canBeSource` gates skip enumeration and download and hold
 * Drive to refusing them instead.
 *
 * Everything happens inside a fresh `run-<uuid>` folder under
 * `DRIVE_TEST_ROOT`, and [rootOf] is confined to it, so even "the top of this
 * account" means the run folder.
 */
class DriveContractLiveTest : ProviderContractTest() {

    override fun newProvider(): CloudProvider {
        DriveLive.assumeAvailable()
        val provider = GoogleDriveCloudProvider(tokens, client)
        val run = runBlocking {
            provider.prepareDestination(accountId, DriveObjects.idOf(DriveLive.root!!), CloudPath.of("run-${UUID.randomUUID()}")).id
        }
        createdRuns += run.opaqueId
        return ConfinedToRun(provider, run)
    }

    override fun account(provider: CloudProvider): AccountId = accountId

    override suspend fun rootFolder(provider: CloudProvider): CloudObjectId = provider.rootOf(account(provider))

    override suspend fun seedFolder(provider: CloudProvider, parent: CloudObjectId, name: String): CloudObjectId =
        provider.prepareDestination(account(provider), parent, CloudPath.of(name)).id

    override suspend fun seedFile(provider: CloudProvider, parent: CloudObjectId, name: String, content: ByteArray): CloudObjectId {
        val session = provider.beginUpload(
            account(provider),
            UploadRequest(account(provider), parent, name, content.size.toLong(), null),
        )
        provider.uploadChunk(session, Chunk(offset = 0, bytes = content, isFinal = true))
        return provider.finishUpload(session).id
    }

    @Test
    fun `an expired session is reported as one, so the item restarts rather than fails`() = runBlocking {
        val provider = newProvider()
        val root = rootFolder(provider)
        val session = provider.beginUpload(accountId, UploadRequest(accountId, root, "expired.bin", 1024L * 1024, null))
        provider.abortUpload(session)

        // §22.5 against the real service: a session Drive no longer holds.
        val failure = assertFailsWith<dev.thiagosindra.cloudlug.provider.CloudException> { provider.queryUpload(session) }
        kotlin.test.assertEquals(dev.thiagosindra.cloudlug.provider.CloudErrorKind.UPLOAD_SESSION_EXPIRED, failure.kind)
    }

    /** Delegation, so every method but [rootOf] is the real adapter's. */
    private class ConfinedToRun(delegate: CloudProvider, private val runRoot: CloudObjectId) : CloudProvider by delegate {
        override fun rootOf(account: AccountId): CloudObjectId = runRoot
    }

    companion object {
        private val client = OkHttpClient()
        private val tokens by lazy { DriveLive.Tokens(client) }
        private val createdRuns = mutableListOf<String>()

        private val accountId: AccountId by lazy {
            runBlocking { GoogleDriveCloudProvider(tokens, client).authenticate().id }
        }

        /** Removes every run folder; failures are swallowed so they cannot hide the test failure that caused them. */
        @JvmStatic
        @AfterAll
        fun removeWhatTheRunCreated() {
            if (!DriveLive.isConfigured) return
            runBlocking {
                val api = DriveApi(tokens, client)
                createdRuns.forEach { id -> runCatching { api.delete(accountId, id) } }
            }
            createdRuns.clear()
        }
    }
}
