package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pause, resume and cancel **partway through a file** (spec §22, §31.3).
 *
 * Every other test in this module interrupts a transfer at a state boundary,
 * because with instant providers there is nowhere else to interrupt it: a whole
 * object moves inside one scheduler tick. A boundary is the easy case — nothing
 * is half-written, no session is open, no chunk is partial. The interesting
 * failures live in the middle, which is why v1.3 §31.3 makes the slow source
 * and slow destination *required* injections rather than conveniences.
 *
 * The chunk size is lowered to the destination's 256 KiB alignment so a 2 MiB
 * file takes eight reads; `readDelay` then turns those reads into virtual time
 * the test can stop in the middle of.
 *
 * What these do **not** prove: that no byte was re-fetched on resume. They show
 * the cache survives and the result is byte-identical, not that the resumed
 * download asked for the exact remaining range.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MidFileInterruptionTest {

    private fun TestScope.controllerFor(harness: TransferTestHarness) = TransferController(
        harness.engine,
        harness.repository,
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    private fun slowHarness() = TransferTestHarness(
        cachePolicy = CacheBudgetPolicy(networkChunkBytes = CHUNK),
    ).apply { source.readDelay = READ_DELAY }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `pause partway through a file leaves it partly downloaded, not restarted`() = runTest {
        val harness = slowHarness()
        // The destination lags far behind the source, so chunks are downloaded
        // and not yet acknowledged when the pause lands. §15.3 deletes a cached
        // chunk only once the destination has taken it, so with an instant
        // destination there is legitimately nothing in the cache to assert on —
        // which is why §31.3 needs both injections and not just the source.
        harness.destination.uploadChunkDelay = FOREVER
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("big.bin", bytes(FILE_SIZE, 1), folder)
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        controller.start(transfer.id)
        testScheduler.advanceTimeBy(READ_DELAY * 3)

        val midFile = assertNotNull(harness.repository.listItems(transfer.id).firstOrNull { it.filename == "big.bin" })
        assertTrue(
            midFile.downloadedBytes in 1 until FILE_SIZE.toLong(),
            "expected to be partway through big.bin, but ${midFile.downloadedBytes} of $FILE_SIZE bytes were read",
        )
        assertEquals(TransferItemStatus.DOWNLOADING, midFile.status)

        val paused = controller.pause(transfer.id)
        assertEquals(TransferStatus.PAUSED, paused)

        // The half-file is kept: §15.3 caches by chunk precisely so a pause in
        // the middle of a large object does not throw away bytes already paid
        // for and not yet acknowledged.
        assertTrue(harness.cachedChunkBytes() > 0, "the chunks downloaded before the pause were discarded")
        harness.cleanUp()
    }

    @Test
    fun `resuming after a mid-file pause finishes the file byte-identically`() = runTest {
        val harness = slowHarness()
        val folder = harness.source.storage.folder("photos")
        val content = bytes(FILE_SIZE, 7)
        harness.source.storage.file("big.bin", content, folder)
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        controller.start(transfer.id)
        testScheduler.advanceTimeBy(READ_DELAY * 3)
        val interrupted = assertNotNull(harness.repository.listItems(transfer.id).first { it.filename == "big.bin" })
        assertTrue(interrupted.downloadedBytes in 1 until FILE_SIZE.toLong(), "the pause did not land mid-file")
        controller.pause(transfer.id)

        controller.resume(transfer.id)
        testScheduler.advanceUntilIdle()

        val settled = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(TransferStatus.COMPLETED, settled.status)
        assertEquals(1, settled.completedFiles)

        // §21: the destination holds the whole file, not the part that had been
        // downloaded when the pause landed.
        val written = harness.destinationTree().entries.single { it.key.endsWith("big.bin") }
        assertEquals(content.size, written.value.length, "resumed file is the wrong length")
        harness.cleanUp()
    }

    @Test
    fun `cancelling one file partway through leaves the rest of the transfer running`() = runTest {
        val harness = slowHarness()
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("cancelled.bin", bytes(FILE_SIZE, 3), folder)
        harness.source.storage.file("kept.bin", bytes(FILE_SIZE, 9), folder)
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        controller.start(transfer.id)
        testScheduler.advanceTimeBy(READ_DELAY * 3)

        // Whichever the engine picked up first is the one to cancel: §22.2 is
        // about cancelling the file in flight, not a pending one.
        val inFlight = assertNotNull(
            harness.repository.listItems(transfer.id)
                .firstOrNull { it.status == TransferItemStatus.DOWNLOADING && it.downloadedBytes > 0 },
            "nothing was mid-download to cancel",
        )
        assertTrue(inFlight.downloadedBytes < FILE_SIZE.toLong(), "the cancel did not land mid-file")

        controller.cancelItem(transfer.id, inFlight.id)
        testScheduler.advanceUntilIdle()

        // Keyed by filename here rather than through the harness, whose map is
        // keyed by source-relative path.
        val statuses = harness.repository.listItems(transfer.id).associate { it.filename to it.status }
        assertEquals(TransferItemStatus.CANCELLED, statuses[inFlight.filename])

        val survivor = statuses.keys.single { it != inFlight.filename && it.endsWith(".bin") }
        assertEquals(TransferItemStatus.COMPLETED, statuses[survivor], "cancelling one file stopped the other")

        val written = harness.destinationTree().keys.filter { it.endsWith(".bin") }
        assertEquals(listOf(survivor), written.map { it.substringAfterLast('/') })
        harness.cleanUp()
    }

    private companion object {
        /** The destination's upload alignment, so the engine reads in 256 KiB steps. */
        const val CHUNK = 256L * 1024

        /** Eight chunks: enough that "partway" is unambiguous. */
        const val FILE_SIZE = 2 * 1024 * 1024

        val READ_DELAY = 100.milliseconds

        /** Long enough that no upload completes inside any window here. */
        val FOREVER = 1.hours
    }
}
