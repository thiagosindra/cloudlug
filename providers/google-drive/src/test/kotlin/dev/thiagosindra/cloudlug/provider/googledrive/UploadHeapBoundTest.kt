package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.network.RedactingLogInterceptor
import dev.thiagosindra.cloudlug.provider.CloudDownload
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import dev.thiagosindra.cloudlug.storage.FileSystemChunkStore
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import dev.thiagosindra.cloudlug.transfer.TransferEngine
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The heap an upload costs does not grow with the bytes moved.
 *
 * A Dropbox → Drive transfer of 2.5 GB on a phone died with
 * `OutOfMemoryError` mid-upload, on a 256 MB heap. Each chunk is 8 MiB, so a
 * heap that fills is one where something outlives the chunk it belonged to.
 *
 * This runs what the phone ran — the real engine, the real Drive adapter and
 * OkHttp — against a MockWebServer that answers like Drive, with a source
 * that *generates* its bytes so the test's own data never sits in the heap.
 * After every chunk Drive receives, the heap is measured after a full GC; the
 * retained size must not climb from one file to the next.
 *
 * The server's own request log is drained as it goes. Over HTTP/2,
 * MockWebServer keeps every request body regardless of `bodyLimit`, and the
 * first version of this test measured that — 9 MiB a chunk — and nearly
 * blamed CloudLug for it.
 *
 * HTTP/2 makes this the slowest test in the JVM suite — about ninety seconds,
 * against five over HTTP/1.1, because MockWebServer's HTTP/2 server is slow —
 * and it is kept anyway: HTTP/2 is what the phone speaks, and its framing
 * buffers are exactly where a retained chunk could hide.
 *
 * On the code that crashed, this passes: one pipeline's heap is flat. What
 * multiplied it on the phone was several pipelines at once, which
 * `OneRunnerPerTransferTest` covers. This one stays so that a retained chunk
 * fails on every PR rather than on a phone after two gigabytes.
 */
class UploadHeapBoundTest {

    private val server = MockWebServer().apply {
        bodyLimit = 0L
        // HTTP/2, as Drive speaks it: a different framing path through OkHttp
        // from HTTP/1.1, with its own buffers.
        protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
    }
    private val cacheRoot = Files.createTempDirectory("cloudlug-heap")

    @AfterTest
    fun tearDown() {
        server.shutdown()
        Files.walk(cacheRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `the heap stays bounded across 120 MiB of uploads in 8 MiB chunks`() = runBlocking {
        val heapAfterChunk = Collections.synchronizedList(mutableListOf<Long>())
        val sessions = AtomicInteger()
        val finished = Collections.synchronizedSet(mutableSetOf<String>())

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val uploadId = request.requestUrl!!.queryParameter("upload_id")
                return when {
                    path.endsWith("/about") -> DriveFixtures.response("about_get_200")
                    request.method == "GET" && path.startsWith("/drive/v3/files/file-") ->
                        fileMetadata(path.substringAfterLast("/file-"))
                    request.method == "GET" && path.startsWith("/drive/v3/files/") -> DriveFixtures.response("files_get_folder_200")
                    request.method == "GET" && path.endsWith("/files") -> DriveFixtures.response("files_list_by_name_none_200")
                    request.method == "POST" && path == "/drive/v3/files" -> DriveFixtures.response("files_create_folder_200")
                    request.method == "POST" && path.startsWith("/upload/") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=s${sessions.incrementAndGet()}")
                    request.method == "PUT" && request.bodySize == 0L ->
                        if (uploadId in finished) fileMetadata(uploadId!!) else MockResponse().setResponseCode(308)
                    request.method == "PUT" -> {
                        // MockWebServer logs every request it serves, and over HTTP/2
                        // it logs the body whatever bodyLimit says; drained, so
                        // the server's memory is not mistaken for CloudLug's.
                        while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) Unit
                        heapAfterChunk += retainedHeap()
                        val (last, total) = contentRange(request.getHeader("Content-Range")!!)
                        if (last + 1 == total) {
                            finished += uploadId!!
                            fileMetadata(uploadId)
                        } else {
                            MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-$last")
                        }
                    }
                    else -> MockResponse().setResponseCode(500).setBody("unexpected ${request.method} $path")
                }
            }
        }

        val clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC)
        val repository = TransferRepository(InMemoryCloudLugDatabase(), clock)
        val source = GeneratedSource(FILES, FILE_BYTES)
        val drive = GoogleDriveCloudProvider(StaticTokens(), appLikeClient(), clock = clock::instant)
        val engine = TransferEngine(
            repository = repository,
            providers = ProviderRegistry { type -> if (type == ProviderType.DROPBOX) source else drive },
            chunkStore = FileSystemChunkStore(cacheRoot),
            networkMonitor = NetworkMonitor { NetworkState.UNMETERED },
            storageMonitor = StorageMonitor { StorageSnapshot(freeBytes = 40L shl 30, totalBytes = 64L shl 30) },
            cachePolicy = CacheBudgetPolicy(),
            clock = clock,
            zone = ZoneOffset.UTC,
        )
        val transfer = repository.createTransfer(
            TransferEntity(
                id = TransferId("heap"),
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
                sourceProvider = ProviderType.DROPBOX,
                sourceAccountId = SOURCE_ACCOUNT,
                destinationProvider = ProviderType.GOOGLE_DRIVE,
                destinationAccountId = ACCOUNT,
                destinationRootId = DriveObjects.ROOT,
                destinationContainerName = "CloudLug - heap",
                networkPolicy = TransferNetworkPolicy.ANY_NETWORK,
            ),
        )

        engine.prepare(transfer.id, CloudSelection.of(SOURCE_ACCOUNT, listOf(source.folder())))
        engine.start(transfer.id)
        val outcome = engine.run(transfer.id)

        val items = repository.listItems(transfer.id).filter { it.filename.endsWith(".bin") }
        assertEquals(TransferStatus.COMPLETED, outcome, items.joinToString { "${it.filename}: ${it.status} ${it.lastErrorCode}" })
        assertTrue(items.all { it.status == TransferItemStatus.COMPLETED })

        // The rule this file exists for (docs/testing.md rule 2): a run that
        // measured nothing would pass any bound.
        val chunks = FILES * (FILE_BYTES / CHUNK)
        assertEquals(chunks.toInt(), heapAfterChunk.size, "every chunk must have been measured")

        // Settled after the first file, the heap may wobble but not climb:
        // the last file's worst must sit within two chunks of the first's.
        val perFile = (FILE_BYTES / CHUNK).toInt()
        val firstFileWorst = heapAfterChunk.subList(0, perFile).max()
        val lastFileWorst = heapAfterChunk.subList(heapAfterChunk.size - perFile, heapAfterChunk.size).max()
        val growth = lastFileWorst - firstFileWorst
        assertTrue(
            growth < 2 * CHUNK,
            "retained heap grew ${growth shr 20} MiB over ${chunks} chunks " +
                "(per chunk, MiB: ${heapAfterChunk.joinToString { (it shr 20).toString() }})",
        )
    }

    /** The app's client as far as a test can have it: §26's interceptor, over HTTP/2. */
    private fun appLikeClient() = localClient(server).newBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .addInterceptor(RedactingLogInterceptor())
        .build()

    /**
     * Retained heap after a full collection. `System.gc()` is a request; a
     * second pass and a short settle make it one the JVM honours in practice,
     * and MemoryMXBean reads what is live rather than what is reserved.
     */
    private fun retainedHeap(): Long {
        repeat(2) {
            System.gc()
            Thread.sleep(20)
        }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun contentRange(header: String): Pair<Long, Long> {
        val (range, total) = header.removePrefix("bytes ").split('/')
        return range.substringAfter('-').toLong() to total.toLong()
    }

    private fun fileMetadata(uploadId: String): MockResponse = MockResponse().setResponseCode(200).setBody(
        """{"id":"file-$uploadId","name":"x.bin","mimeType":"application/octet-stream","trashed":false,""" +
            """"parents":["FIXTURE0006xxxxxxxxxxxxxxxxxxxxxx"],"version":"1",""" +
            """"md5Checksum":"$expectedMd5","sha256Checksum":"$expectedSha256","size":"$FILE_BYTES"}""",
    )

    /**
     * Files whose bytes are computed as they are read. The fake keeps every
     * object's content in memory, which at this size would be the heap this
     * test measures; it is used here for the tree and nothing else.
     */
    private class GeneratedSource(
        files: Int,
        private val size: Long,
        private val fake: FakeCloudProvider = FakeCloudProvider(type = ProviderType.DROPBOX, accountId = SOURCE_ACCOUNT),
    ) : CloudProvider by fake {

        private val root = fake.storage.folder("photos")

        init {
            repeat(files) { fake.storage.file("file-$it.bin", ByteArray(0), root) }
        }

        fun folder(): CloudObject = fake.storage.find(root.opaqueId)!!

        private fun sized(obj: CloudObject) =
            if (obj.type == dev.thiagosindra.cloudlug.model.CloudObjectType.FILE) obj.copy(size = size, providerHash = null) else obj

        override fun enumerate(account: AccountId, selection: CloudSelection, resumeAfter: CloudObjectId?): Flow<CloudObject> =
            fake.enumerate(account, selection, resumeAfter).map(::sized)

        override suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject =
            sized(fake.resolveMetadata(account, objectId))

        override suspend fun openDownload(account: AccountId, objectId: CloudObjectId, range: LongRange?): CloudDownload =
            Generated(size, fake.resolveMetadata(account, objectId).revision)
    }

    private class Generated(private val size: Long, override val revision: String?) : CloudDownload {
        private var position = 0L
        override val range: LongRange? = null
        override val contentLength: Long = size

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            fill(position, destination, offset, count)
            position += count
            return count
        }

        override fun close() = Unit
    }

    private companion object {
        val SOURCE_ACCOUNT = AccountId("source")
        const val CHUNK = 8L shl 20
        const val FILES = 3
        const val FILE_BYTES = 5 * CHUNK

        /** Byte `p` of every file is `p % 251`; copied from one period, not computed per byte. */
        private val PERIOD = ByteArray(251 * 4096) { (it % 251).toByte() }

        fun fill(position: Long, into: ByteArray, at: Int, count: Int) {
            var done = 0
            while (done < count) {
                val from = ((position + done) % 251).toInt()
                val take = minOf(count - done, PERIOD.size - from)
                System.arraycopy(PERIOD, from, into, at + done, take)
                done += take
            }
        }

        private fun digestOfOneFile(algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            val block = ByteArray(1 shl 16)
            var position = 0L
            while (position < FILE_BYTES) {
                val count = minOf(block.size.toLong(), FILE_BYTES - position).toInt()
                fill(position, block, 0, count)
                digest.update(block, 0, count)
                position += count
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val expectedMd5 = digestOfOneFile("MD5")
        val expectedSha256 = digestOfOneFile("SHA-256")
    }
}
