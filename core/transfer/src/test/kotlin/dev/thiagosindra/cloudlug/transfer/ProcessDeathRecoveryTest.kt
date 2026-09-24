package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.UploadRequest
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * What the engine finds when it comes back to a transfer the process died
 * inside (spec §2.4, §31.4).
 *
 * Process death is not pause. Pause unwinds: the coroutine is cancelled, every
 * `finally` runs, and the rows are left describing a transfer that stopped
 * tidily. Death leaves whatever was written at the instant the process went
 * away — an item recorded as UPLOADING with no upload happening, a session id
 * naming a session on a server that is still holding bytes, and nothing in
 * memory that knows any of it.
 *
 * Every other test in this module resumes from a pause, which is why both of
 * the defects below survived: §31.4's scenarios were only ever run against
 * state that had been unwound first.
 *
 * The rows here are written directly rather than produced by killing a real
 * run, because a `TestScope` cannot be killed — cancelling it *is* the pause
 * case. Writing the state is the only way to get the one that matters.
 */
class ProcessDeathRecoveryTest {

    private fun harness() = TransferTestHarness()

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it + seed) % 251).toByte() }

    /**
     * The item was mid-upload; the session on the destination is open, has
     * taken part of the object, and has not expired.
     *
     * This is the ordinary shape of a force-stop during a large file, and it
     * used to end the whole transfer twice over: `recoveryStatusFor` sent the
     * item to CACHED, which `FileTransferWorker` cannot re-enter, and had it
     * got past that, the first chunk would have gone to a session already
     * holding bytes and come back `incorrect_offset`.
     */
    @Test
    fun `a file that was uploading when the process died is transferred whole`() = runTest {
        val harness = harness()
        val content = bytes(FILE_SIZE, 11)
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("big.bin", content, folder)

        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))
        harness.engine.start(transfer.id)

        val item = harness.repository.listItems(transfer.id).single { it.filename == "big.bin" }
        val session = harness.destination.beginUpload(
            AccountId("destination-account"),
            UploadRequest(
                account = AccountId("destination-account"),
                parent = harness.destinationRoot,
                name = item.filename,
                size = item.size,
                mimeType = item.mimeType,
            ),
        )
        val sent = content.copyOfRange(0, ACKNOWLEDGED)
        harness.destination.uploadChunk(session, Chunk(0L, sent, sent.size, isFinal = false))
        // Far enough ahead that `beginOrResumeUpload` reuses it. A real
        // provider's session outlives the process by days; the fake's default
        // expiry is Instant.EPOCH-relative and so always reads as expired,
        // which is exactly why no existing test reached this path.
        harness.repository.recordUploadSession(
            item.id,
            session.id,
            session.providerMetadata,
            Instant.parse("2027-01-01T00:00:00Z"),
        )
        harness.repository.recordUploadProgress(item.id, ACKNOWLEDGED.toLong())
        harness.repository.transitionItem(item.id, TransferItemStatus.CHECKING_DESTINATION)
        harness.repository.transitionItem(item.id, TransferItemStatus.DOWNLOADING)
        harness.repository.transitionItem(item.id, TransferItemStatus.CACHED)
        harness.repository.transitionItem(item.id, TransferItemStatus.UPLOADING)

        // The process comes back with nothing but rows (§2.4).
        harness.repository.recoverInterruptedItems(transfer.id)
        assertEquals(TransferStatus.COMPLETED, harness.engine.run(transfer.id))

        assertEquals(
            TransferItemStatus.COMPLETED,
            harness.repository.findItem(item.id)?.status,
            "the file that was uploading when the process died did not finish",
        )
        // §32.1: whole, not the part the abandoned session was holding.
        val written = assertNotNull(harness.repository.findItem(item.id)?.destinationObjectId)
        assertContentEquals(
            content,
            harness.destination.storage.contentOf(written),
            "the destination holds something other than the source file",
        )
        assertFalse(
            harness.destination.hasOpenSessions(),
            "the session the dead process left open was never abandoned",
        )
        harness.cleanUp()
    }

    /**
     * The same death one step later, while the item was being verified.
     *
     * Verification compares the uploaded object against hashes computed during
     * the pass that produced it, and that pass is gone with the process — so
     * the item has to be re-entered from the top, not resumed. Recovery left
     * VERIFYING untouched instead, and VERIFYING cannot reach
     * CHECKING_DESTINATION: the next run threw and took the transfer with it.
     *
     * Run separately from the UPLOADING case because they are separate arms of
     * `recoveryStatusFor`, and the first fix landed without the second.
     */
    @Test
    fun `a file that was being verified when the process died is re-entered and completes`() = runTest {
        val harness = harness()
        val content = bytes(FILE_SIZE, 23)
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("big.bin", content, folder)

        val transfer = harness.createTransfer()
        harness.engine.prepare(transfer.id, harness.selectionOf(folder))
        harness.engine.start(transfer.id)

        val item = harness.repository.listItems(transfer.id).single { it.filename == "big.bin" }
        harness.repository.transitionItem(item.id, TransferItemStatus.CHECKING_DESTINATION)
        harness.repository.transitionItem(item.id, TransferItemStatus.DOWNLOADING)
        harness.repository.transitionItem(item.id, TransferItemStatus.CACHED)
        harness.repository.transitionItem(item.id, TransferItemStatus.UPLOADING)
        harness.repository.transitionItem(item.id, TransferItemStatus.VERIFYING)

        harness.repository.recoverInterruptedItems(transfer.id)
        assertEquals(TransferStatus.COMPLETED, harness.engine.run(transfer.id))

        assertEquals(
            TransferItemStatus.COMPLETED,
            harness.repository.findItem(item.id)?.status,
            "the item left mid-verification never settled",
        )
        val written = assertNotNull(harness.repository.findItem(item.id)?.destinationObjectId)
        assertContentEquals(content, harness.destination.storage.contentOf(written))
        harness.cleanUp()
    }

    private companion object {
        /** Two 8 MiB chunks' worth is more than this test needs; one is enough. */
        const val FILE_SIZE = 512 * 1024

        /** What the destination had taken when the process died. */
        const val ACKNOWLEDGED = 256 * 1024
    }
}
