package dev.thiagosindra.cloudlug.transfer.schedule

import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.transfer.TransferTestHarness
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A transfer whose row says RUNNING after its process died is interrupted, not
 * running, and reconciliation gives it back to the platform.
 *
 * The 2.5 GB phone run ended in `OutOfMemoryError`, and afterwards the row
 * still said RUNNING with no worker anywhere. The row is right about what was
 * happening and wrong about what is; only a scheduler can make it true again.
 */
class ReconcileOrphanedRunTest {

    private val harness = TransferTestHarness()

    @AfterTest
    fun tearDown() = harness.cleanUp()

    @Test
    fun `a RUNNING transfer with no job is enqueued`() = runTest {
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("a.bin", ByteArray(1024), folder)
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))
        // Started, and then the process died: the row stays RUNNING and nothing
        // in this process is running it.
        harness.engine.start(transfer.id)
        assertEquals(TransferStatus.RUNNING, harness.repository.findTransfer(transfer.id)?.status)

        val enqueued = mutableListOf<TransferId>()
        SchedulingPolicy.reconcile(harness.repository.listTransfers()) { enqueued += it.id }

        assertEquals(listOf(transfer.id), enqueued)
    }

    @Test
    fun `a job the platform is executing is never replaced`() {
        // Replacing it stops the running job and starts a second pipeline
        // beside the first, which has not stopped yet (v0.6.1).
        assertFalse(SchedulingPolicy.shouldSchedule(PlatformJobState.EXECUTING))
    }

    @Test
    fun `a waiting job, or none, is scheduled`() {
        // Waiting includes the backoff a crashed run leaves behind; a fresh job
        // is how an interrupted transfer resumes when the app is opened.
        assertTrue(SchedulingPolicy.shouldSchedule(PlatformJobState.WAITING))
        assertTrue(SchedulingPolicy.shouldSchedule(PlatformJobState.NONE))
    }
}
