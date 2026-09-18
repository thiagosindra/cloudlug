package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.fake.FailureInjection
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.provider.fake.ProcessInterruptedException
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end behaviour of the engine against two fake providers and a real
 * on-disk chunk cache. These are the tests that would catch a regression in the
 * engineering invariants of spec §32.
 */
class TransferEngineTest {

    private val harness = TransferTestHarness()

    @AfterTest
    fun cleanUp() = harness.cleanUp()

    @Test
    fun `a tree is transferred with its layout and empty folders preserved`() = runTest {
        val photos = harness.source.storage.folder("photos")
        val year = harness.source.storage.folder("2026", photos)
        harness.source.storage.file("1.png", "first".toByteArray(), year)
        harness.source.storage.file("2.png", "second".toByteArray(), year)
        harness.source.storage.folder("empty", photos)

        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(photos))
        val status = harness.engine.run(transfer.id)

        assertEquals(TransferStatus.COMPLETED, status)
        val container = "CloudLug - 2026-09-17 09-57"
        assertEquals(
            mapOf(
                container to "<folder>",
                "$container/photos" to "<folder>",
                "$container/photos/2026" to "<folder>",
                "$container/photos/2026/1.png" to "first",
                "$container/photos/2026/2.png" to "second",
                "$container/photos/empty" to "<folder>",
            ),
            harness.destinationTree(),
            "spec §10 and §20.5: original relative paths and empty folders are preserved",
        )
    }

    @Test
    fun `completion requires destination verification`() = runTest {
        val content = "verify me".toByteArray()
        val file = harness.source.storage.file("data.bin", content)
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(file))
        harness.engine.run(transfer.id)

        val item = harness.repository.listItems(transfer.id).single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals(ItemStatusReason.VERIFIED_BY_DESTINATION_HASH, item.statusReason)
        assertEquals(
            FakeCloudProvider.hash(content, HashAlgorithm.MD5).value,
            assertNotNull(item.computedDestinationNativeHash).value,
            "the destination-native hash is computed locally while streaming (spec §19.4)",
        )
        assertEquals(
            FakeCloudProvider.hash(content, HashAlgorithm.SHA256).value,
            assertNotNull(item.computedSha256).value,
        )
    }

    @Test
    fun `corrupted bytes are caught by verification and never marked complete`() = runTest {
        val file = harness.source.storage.file("data.bin", "trustworthy".toByteArray())
        harness.destination.inject(
            FailureInjection(FailureInjection.Fault.CORRUPT_CHUNK, FailureInjection.Operation.UPLOAD_CHUNK),
        )
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(file))
        val status = harness.engine.run(transfer.id)

        val item = harness.repository.listItems(transfer.id).single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertEquals("verification_mismatch", item.lastErrorCode)
        assertEquals(TransferStatus.COMPLETED_WITH_ERRORS, status)
    }

    @Test
    fun `native documents and shortcuts are skipped at manifest time`() = runTest {
        val folder = harness.source.storage.folder("mixed")
        harness.source.storage.file("real.txt", "bytes".toByteArray(), folder)
        harness.source.storage.nativeDocument("Plan", folder)
        harness.source.storage.shortcut("Link", folder)

        val transfer = harness.createTransfer()
        val summary = harness.engine.prepare(transfer.id, harness.selectionOf(folder))
        harness.engine.run(transfer.id)

        assertEquals(2, summary.unsupported)
        val statuses = harness.statusesByName(transfer.id)
        assertEquals(TransferItemStatus.SKIPPED_UNSUPPORTED, statuses["mixed/Plan"])
        assertEquals(TransferItemStatus.SKIPPED_UNSUPPORTED, statuses["mixed/Link"])
        assertEquals(TransferItemStatus.COMPLETED, statuses["mixed/real.txt"])
    }

    @Test
    fun `an existing destination object with different content is a conflict, never an overwrite`() = runTest {
        val file = harness.source.storage.file("report.txt", "new content".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        // Something else put a different file at the destination first.
        val container = assertNotNull(harness.repository.findTransfer(transfer.id)?.destinationContainerId)
        harness.destination.storage.file(
            "report.txt",
            "existing content".toByteArray(),
            dev.thiagosindra.cloudlug.provider.CloudObjectId(harness.destination.type, container),
        )

        val status = harness.engine.run(transfer.id)

        val item = harness.repository.listItems(transfer.id).single()
        assertEquals(TransferItemStatus.CONFLICT, item.status)
        assertEquals(TransferStatus.COMPLETED_WITH_ERRORS, status)
        assertEquals(
            "existing content",
            harness.destinationTree()["CloudLug - 2026-09-17 09-57/report.txt"],
            "invariant §32.2: the existing object is untouched",
        )
    }

    @Test
    fun `a source edited after the manifest was reviewed becomes SOURCE_CHANGED`() = runTest {
        val file = harness.source.storage.file("data.bin", "original".toByteArray(), revision = "rev-1")
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        harness.source.storage.mutate(file.opaqueId, "edited".toByteArray(), revision = "rev-2")

        harness.engine.run(transfer.id)

        val item = harness.repository.listItems(transfer.id).single()
        assertEquals(TransferItemStatus.SOURCE_CHANGED, item.status)
        assertEquals(ItemStatusReason.SOURCE_REVISION_CHANGED, item.statusReason)
        assertNull(
            harness.destinationTree()["CloudLug - 2026-09-17 09-57/data.bin"],
            "spec §20.6: a newer version is never silently transferred",
        )
    }

    @Test
    fun `throttling is retried and the transfer still completes`() = runTest {
        val file = harness.source.storage.file("data.bin", "payload".toByteArray())
        harness.destination.inject(
            FailureInjection(
                FailureInjection.Fault.TOO_MANY_REQUESTS,
                FailureInjection.Operation.UPLOAD_CHUNK,
                times = 2,
            ),
        )
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(file))
        val status = harness.engine.run(transfer.id)

        assertEquals(TransferStatus.COMPLETED, status)
        assertTrue(harness.repository.listItems(transfer.id).single().retryCount >= 2)
    }

    @Test
    fun `an expired token holds the transfer for authentication rather than failing it`() = runTest {
        val file = harness.source.storage.file("data.bin", "payload".toByteArray())
        harness.source.inject(
            FailureInjection(
                FailureInjection.Fault.TOKEN_EXPIRED,
                FailureInjection.Operation.RESOLVE_METADATA,
                times = Int.MAX_VALUE,
            ),
        )
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(file))
        val status = harness.engine.run(transfer.id)

        assertEquals(TransferStatus.AUTH_REQUIRED, status)
        assertEquals(TransferItemStatus.CHECKING_DESTINATION, harness.repository.listItems(transfer.id).single().status)
    }

    @Test
    fun `a full destination holds for storage and resumes once space appears`() = runTest {
        val file = harness.source.storage.file("data.bin", "payload".toByteArray())
        harness.destination.inject(
            FailureInjection(FailureInjection.Fault.DESTINATION_FULL, FailureInjection.Operation.UPLOAD_CHUNK),
        )
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        assertEquals(TransferStatus.WAITING_FOR_STORAGE, harness.engine.run(transfer.id))

        // The user freed space at the destination; the injection is spent.
        assertEquals(TransferStatus.COMPLETED, harness.engine.resume(transfer.id))
        assertEquals("payload", harness.destinationTree()["CloudLug - 2026-09-17 09-57/data.bin"])
    }

    @Test
    fun `a metered network holds an unmetered-only transfer and never falls back to it`() = runTest {
        val file = harness.source.storage.file("data.bin", "payload".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        harness.onNetwork(NetworkState.METERED)
        assertEquals(TransferStatus.WAITING_FOR_WIFI, harness.engine.run(transfer.id))
        assertTrue(harness.destinationTree().none { it.key.endsWith("data.bin") }, "invariant §32.5")

        harness.onNetwork(NetworkState.UNMETERED)
        assertEquals(TransferStatus.COMPLETED, harness.engine.resume(transfer.id))
    }

    @Test
    fun `local storage pressure holds the transfer instead of filling the device`() = runTest {
        val file = harness.source.storage.file("data.bin", "payload".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        // Every free byte is inside the emergency reserve (spec §15.1).
        harness.withStorage(StorageSnapshot(freeBytes = 512L * 1024 * 1024, totalBytes = 16L * 1024 * 1024 * 1024))
        assertEquals(TransferStatus.WAITING_FOR_STORAGE, harness.engine.run(transfer.id))

        harness.withStorage(StorageSnapshot(freeBytes = 40L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024))
        assertEquals(TransferStatus.COMPLETED, harness.engine.resume(transfer.id))
    }

    @Test
    fun `the cache is empty once a transfer completes`() = runTest {
        val folder = harness.source.storage.folder("tree")
        repeat(3) { harness.source.storage.file("file-$it.bin", ByteArray(2_000) { b -> b.toByte() }, folder) }
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(folder))
        harness.engine.run(transfer.id)

        assertEquals(0, harness.cachedChunkBytes(), "chunks are deleted once acknowledged (spec §32.4)")
        assertEquals(0, harness.repository.totalCachedBytes())
    }

    @Test
    fun `cancelling mid-transfer leaves completed files alone and clears the rest`() = runTest {
        val folder = harness.source.storage.folder("tree")
        harness.source.storage.file("a.bin", "a".toByteArray(), folder)
        harness.source.storage.file("b.bin", "b".toByteArray(), folder)
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))

        // Wi-Fi drops as soon as the first file has landed, so the second never starts.
        harness.onNetwork {
            val firstFileLanded = harness.destinationTree().keys.any { it.endsWith("tree/a.bin") }
            if (firstFileLanded) NetworkState.METERED else NetworkState.UNMETERED
        }
        assertEquals(TransferStatus.WAITING_FOR_WIFI, harness.engine.run(transfer.id))

        val status = harness.engine.cancelTransfer(transfer.id)

        assertEquals(TransferStatus.CANCELLED, status)
        assertEquals(
            "a",
            harness.destinationTree()["CloudLug - 2026-09-17 09-57/tree/a.bin"],
            "spec §22.3: already completed destination files remain untouched",
        )
        val statuses = harness.statusesByName(transfer.id)
        assertEquals(TransferItemStatus.COMPLETED, statuses["tree/a.bin"])
        assertEquals(TransferItemStatus.CANCELLED, statuses["tree/b.bin"])
        assertEquals(0, harness.cachedChunkBytes(), "local chunks of unfinished work are removed")
        assertFalse(harness.destination.hasOpenSessions(), "no resumable session is left dangling")
    }

    @Test
    fun `retry incomplete re-queues only what did not finish`() = runTest {
        val folder = harness.source.storage.folder("tree")
        val good = harness.source.storage.file("good.bin", "fine".toByteArray(), folder)
        harness.source.storage.file("clash.bin", "new".toByteArray(), folder)
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))

        val container = assertNotNull(harness.repository.findTransfer(transfer.id)?.destinationContainerId)
        val treeFolder = harness.destination.storage.folder(
            "tree",
            dev.thiagosindra.cloudlug.provider.CloudObjectId(harness.destination.type, container),
        )
        harness.destination.storage.file("clash.bin", "existing".toByteArray(), treeFolder)

        harness.engine.run(transfer.id)
        assertEquals(TransferItemStatus.CONFLICT, harness.statusesByName(transfer.id)["tree/clash.bin"])

        val requeued = harness.engine.retryIncomplete(transfer.id)

        assertEquals(1, requeued, "only the conflicted item is retried")
        assertEquals(TransferItemStatus.PENDING, harness.statusesByName(transfer.id)["tree/clash.bin"])
        assertEquals(TransferItemStatus.COMPLETED, harness.statusesByName(transfer.id)["tree/good.bin"])
        assertEquals("fine", harness.destinationTree()["CloudLug - 2026-09-17 09-57/tree/good.bin"])
        assertNotNull(harness.repository.findItem(harness.repository.listItems(transfer.id).first { it.sourceObjectId == good.opaqueId }.id))
    }

    @Test
    fun `a process killed mid-download resumes without duplicating destination objects`() = runTest {
        val folder = harness.source.storage.folder("tree")
        harness.source.storage.file("a.bin", "alpha".toByteArray(), folder)
        harness.source.storage.file("b.bin", "beta".toByteArray(), folder)
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))

        // The process dies partway: this is not a provider error, so nothing
        // settles the item — exactly the §31.4 "kill during download" case.
        harness.source.inject(
            FailureInjection(
                FailureInjection.Fault.PROCESS_INTERRUPTED,
                FailureInjection.Operation.OPEN_DOWNLOAD,
            ),
        )
        assertFailsWith<ProcessInterruptedException> { harness.engine.run(transfer.id) }

        val interrupted = harness.repository.listItems(transfer.id).first { it.filename == "a.bin" }
        assertEquals(TransferItemStatus.DOWNLOADING, interrupted.status, "the item is left mid-flight")

        // Restart: a fresh run over the same authoritative database.
        harness.source.clearInjections()
        val status = harness.engine.run(transfer.id)

        assertEquals(TransferStatus.COMPLETED, status)
        val tree = harness.destinationTree()
        assertEquals("alpha", tree["CloudLug - 2026-09-17 09-57/tree/a.bin"])
        assertEquals("beta", tree["CloudLug - 2026-09-17 09-57/tree/b.bin"])
        assertEquals(
            2,
            tree.keys.count { it.endsWith(".bin") },
            "invariant §32.6: an interrupted transfer can be safely retried",
        )
        assertEquals(0, harness.cachedChunkBytes())
    }
}
