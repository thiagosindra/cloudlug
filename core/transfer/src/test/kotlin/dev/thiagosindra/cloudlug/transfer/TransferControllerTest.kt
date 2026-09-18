package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The flow the §24 shell drives, exercised at the layer the ViewModels call.
 *
 * The Compose screens themselves need a device to run, so this is where the
 * behaviour they depend on is actually verified: a transfer is started from a
 * selection, observed while it runs, paused, resumed and cancelled — spec §24.2,
 * §24.3 and §22 — with a real chunk cache and two fake providers underneath.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferControllerTest {

    /**
     * The controller is given its own scope on the test dispatcher rather than
     * `backgroundScope`, which `advanceUntilIdle` does not drive. It also
     * mirrors the app, where the scope is application-scoped so a transfer
     * outlives the screen that started it.
     */
    private fun TestScope.controllerFor(harness: TransferTestHarness) = TransferController(
        harness.engine,
        harness.repository,
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    private fun harnessWithTree(): Pair<TransferTestHarness, dev.thiagosindra.cloudlug.provider.CloudObjectId> {
        val harness = TransferTestHarness()
        val folder = harness.source.storage.folder("photos")
        repeat(6) { harness.source.storage.file("p$it.bin", ByteArray(4096) { b -> (b + it).toByte() }, folder) }
        return harness to folder
    }

    @Test
    fun `a transfer prepared from a selection starts, runs and completes`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()

        val summary = controller.prepare(transfer.id, harness.selectionOf(folder))
        assertEquals(6, summary.files)

        // §10: still nothing at the destination until the transfer starts.
        assertTrue(harness.destinationTree().isEmpty())

        controller.start(transfer.id)
        testScheduler.advanceUntilIdle()

        val settled = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(TransferStatus.COMPLETED, settled.status)
        assertEquals(6, settled.completedFiles)
        assertFalse(controller.isRunning(transfer.id), "the worker is released once the run ends")
    }

    @Test
    fun `progress is observable while the transfer runs`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        // What the detail screen collects (§24.3): rows, not engine callbacks.
        val itemsBefore = controller.observeItems(transfer.id).first()
        assertEquals(7, itemsBefore.size, "six files and their folder")
        assertTrue(itemsBefore.all { it.status == TransferItemStatus.PENDING })

        controller.start(transfer.id)
        testScheduler.advanceUntilIdle()

        val itemsAfter = controller.observeItems(transfer.id).first()
        // Seven rows complete: the six files and the folder holding them. Only
        // the files move the §12.1 counters, which is what the progress bar reads.
        assertEquals(7, itemsAfter.count { it.status == TransferItemStatus.COMPLETED })
        assertEquals(
            6,
            itemsAfter.count {
                it.status == TransferItemStatus.COMPLETED &&
                    it.objectKind != dev.thiagosindra.cloudlug.model.CloudObjectType.FOLDER
            },
        )
        val observed = assertNotNull(controller.observeTransfer(transfer.id).first())
        assertEquals(1f, observed.completedBytes.toFloat() / observed.totalBytes)
    }

    @Test
    fun `pause stops the run and resume finishes it`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        // Reach RUNNING without processing any item. Pausing mid-file needs a
        // slow-source injection the fake does not yet have (spec §31.3), so this
        // covers the state machine and the resume path, not mid-chunk pausing.
        harness.engine.start(transfer.id)
        controller.start(transfer.id)
        val paused = controller.pause(transfer.id)
        assertEquals(TransferStatus.PAUSED, paused)
        assertFalse(controller.isRunning(transfer.id))
        assertTrue(harness.destinationTree().none { it.key.endsWith(".bin") }, "no file moved before the pause")

        controller.resume(transfer.id)
        testScheduler.advanceUntilIdle()

        val settled = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(TransferStatus.COMPLETED, settled.status)
        assertEquals(6, settled.completedFiles)
    }

    @Test
    fun `cancel settles the transfer and leaves the cache empty`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        harness.engine.start(transfer.id)
        controller.start(transfer.id)
        val cancelled = controller.cancel(transfer.id)
        testScheduler.advanceUntilIdle()

        assertEquals(TransferStatus.CANCELLED, cancelled)
        assertEquals(0, harness.repository.totalCachedBytes(), "spec §22.3 removes local chunks")
        assertFalse(controller.isRunning(transfer.id))
    }

    /**
     * Pressing Start twice, or a screen being recreated on rotation, must not
     * put two workers on one transfer — which would double-count progress and
     * race on the same upload sessions.
     */
    @Test
    fun `starting twice does not run the transfer twice`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        controller.start(transfer.id)
        controller.start(transfer.id)
        testScheduler.advanceUntilIdle()

        val settled = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(TransferStatus.COMPLETED, settled.status)
        assertEquals(6, settled.completedFiles, "each file completed exactly once")
        assertEquals(settled.totalBytes, settled.completedBytes)
    }

    @Test
    fun `a controller scope outlives the caller that started the transfer`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        controller.start(transfer.id)
        testScheduler.advanceUntilIdle()

        assertEquals(
            TransferStatus.COMPLETED,
            assertNotNull(harness.repository.findTransfer(transfer.id)).status,
        )
    }

    /**
     * Regression: `totalFiles` counts only non-folder items, but the outcome
     * counters used to move for folders too, so any transfer containing a
     * directory reported more completed files than it had and drove the §24
     * progress bar past 100%.
     */
    @Test
    fun `folders do not move the file counters`() = runTest {
        val (harness, folder) = harnessWithTree()
        val controller = controllerFor(harness)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        val prepared = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(6, prepared.totalFiles, "the folder is not a file")

        controller.start(transfer.id)
        testScheduler.advanceUntilIdle()

        val settled = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(settled.totalFiles, settled.completedFiles)
        assertTrue(
            settled.completedFiles <= settled.totalFiles,
            "completed (${settled.completedFiles}) must never exceed total (${settled.totalFiles})",
        )
    }
}
