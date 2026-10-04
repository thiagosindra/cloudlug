package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import dev.thiagosindra.cloudlug.storage.FileSystemChunkStore
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import dev.thiagosindra.cloudlug.transfer.TransferEngine
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import dev.thiagosindra.cloudlug.transfer.policy.RetryPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `docs/testing.md` rule 1 for a destination: the real engine moves a file
 * from the fake source through the **real Drive adapter**, and every answer
 * Drive gives is a captured one.
 *
 * The rule exists because every shipped defect in this project lived in a
 * handoff. For a destination the handoff is the upload and §21's verification:
 * what the engine does with a 308, with the file the final chunk returns, and
 * with the checksums in it. So the source here serves **exactly the bytes the
 * capture uploaded** — `i % 251` for 263144 bytes — and the item can only
 * reach COMPLETED if the MD5 and SHA-256 Drive recorded for those bytes agree
 * with what §19.4 computed while streaming them. Nothing in this test supplies
 * a checksum.
 */
class DriveDestinationHandoffTest {

    private val server = MockWebServer()
    private val cacheRoot = Files.createTempDirectory("cloudlug-drive-handoff")

    @AfterTest
    fun tearDown() {
        server.shutdown()
        Files.walk(cacheRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private val captured = ByteArray(256 * 1024 + 1000) { (it % 251).toByte() }

    @Test
    fun `a file moved into Drive completes, verified by the checksums Drive recorded`() = runTest {
        val uploaded = Buffer()
        var finished = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/about") -> DriveFixtures.response("about_get_200")
                    request.method == "GET" && path.endsWith("/files") -> DriveFixtures.response("files_list_by_name_none_200")
                    request.method == "POST" && path == "/drive/v3/files" -> DriveFixtures.response("files_create_folder_200")
                    request.method == "POST" && path.startsWith("/upload/") -> DriveFixtures.response("upload_initiate_200")
                    request.method == "PUT" && request.bodySize == 0L ->
                        DriveFixtures.response(if (finished) "upload_status_after_finish" else "upload_status_nothing_received_308")
                    request.method == "PUT" -> {
                        request.body.copyTo(uploaded)
                        if (uploaded.size == captured.size.toLong()) {
                            finished = true
                            DriveFixtures.response("upload_finish_200")
                        } else {
                            DriveFixtures.response("upload_chunk_308")
                        }
                    }
                    else -> MockResponse().setResponseCode(500).setBody("unexpected ${request.method} $path")
                }
            }
        }

        val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
        val repository = TransferRepository(InMemoryCloudLugDatabase(), clock)
        val source = FakeCloudProvider(type = ProviderType.DROPBOX, accountId = AccountId("source"))
        val folder = source.storage.folder("photos")
        source.storage.file("captured.bin", captured, folder)
        val drive = GoogleDriveCloudProvider(StaticTokens(), localClient(server), clock = clock::instant)

        val engine = TransferEngine(
            repository = repository,
            providers = ProviderRegistry { type -> if (type == ProviderType.DROPBOX) source else drive },
            chunkStore = FileSystemChunkStore(cacheRoot),
            networkMonitor = NetworkMonitor { NetworkState.UNMETERED },
            storageMonitor = StorageMonitor { StorageSnapshot(freeBytes = 40L shl 30, totalBytes = 64L shl 30) },
            cachePolicy = CacheBudgetPolicy(),
            retryPolicy = RetryPolicy(),
            clock = clock,
            zone = ZoneOffset.UTC,
        )
        val transfer = repository.createTransfer(
            TransferEntity(
                id = TransferId("t1"),
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
                sourceProvider = ProviderType.DROPBOX,
                sourceAccountId = AccountId("source"),
                destinationProvider = ProviderType.GOOGLE_DRIVE,
                destinationAccountId = ACCOUNT,
                destinationRootId = DriveObjects.ROOT,
                destinationContainerName = "CloudLug - 2026-10-03 12-00",
                networkPolicy = TransferNetworkPolicy.ANY_NETWORK,
            ),
        )

        engine.prepare(transfer.id, CloudSelection.of(AccountId("source"), listOf(source.storage.find(folder.opaqueId)!!)))
        engine.start(transfer.id)
        val outcome = engine.run(transfer.id)

        val item = repository.listItems(transfer.id).single { it.filename == "captured.bin" }
        assertEquals(TransferStatus.COMPLETED, outcome, "item ended ${item.status} (${item.lastErrorCode}: ${item.lastErrorMessage})")
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals(ItemStatusReason.VERIFIED_BY_DESTINATION_HASH, item.statusReason)
        assertEquals(DriveFixtures.field("upload_finish_200", "id"), assertNotNull(item.destinationObjectId))
        // What reached "Drive" is byte for byte what the source held.
        assertContentEquals(captured, uploaded.readByteArray())
    }
}
