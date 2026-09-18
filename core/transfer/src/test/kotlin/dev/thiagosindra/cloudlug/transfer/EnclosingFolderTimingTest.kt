package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §10: the enclosing folder is created at `READY -> RUNNING`, not during
 * `PREPARING`.
 *
 * The reason is a user-visible one: creating it while preparing leaves an empty
 * folder at the destination every time someone reviews a manifest (§24.2 step 5)
 * and decides not to start.
 */
class EnclosingFolderTimingTest {

    private val harness = TransferTestHarness()

    @Test
    fun `preparing a transfer creates no folder at the destination`() = runTest {
        val file = harness.source.storage.file("a.txt", "hello".toByteArray())
        val transfer = harness.createTransfer()

        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        val prepared = assertNotNull(harness.repository.findTransfer(transfer.id))
        assertEquals(TransferStatus.READY, prepared.status)
        assertNull(prepared.destinationContainerId)
        assertTrue(harness.destinationTree().isEmpty(), "destination should be untouched")
    }

    @Test
    fun `an abandoned manifest leaves nothing behind`() = runTest {
        val file = harness.source.storage.file("a.txt", "hello".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        // The user reviews and cancels instead of starting.
        harness.engine.cancelTransfer(transfer.id)

        assertTrue(harness.destinationTree().isEmpty(), "an abandoned review left a folder behind")
    }

    @Test
    fun `starting the transfer creates the folder exactly once`() = runTest {
        val file = harness.source.storage.file("a.txt", "hello".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        val started = harness.engine.start(transfer.id)
        assertEquals(TransferStatus.RUNNING, started.status)
        val container = assertNotNull(started.destinationContainerId)

        // Starting again — a resume after a pause — must not make a second one.
        val again = harness.engine.start(transfer.id)
        assertEquals(container, again.destinationContainerId)
    }

    @Test
    fun `run creates the folder for callers that never call start`() = runTest {
        val file = harness.source.storage.file("a.txt", "hello".toByteArray())
        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(file))

        harness.engine.run(transfer.id)

        assertNotNull(assertNotNull(harness.repository.findTransfer(transfer.id)).destinationContainerId)
        assertEquals(
            mapOf(
                "CloudLug - 2026-09-17 09-57" to "<folder>",
                "CloudLug - 2026-09-17 09-57/a.txt" to "hello",
            ),
            harness.destinationTree(),
        )
    }
}
