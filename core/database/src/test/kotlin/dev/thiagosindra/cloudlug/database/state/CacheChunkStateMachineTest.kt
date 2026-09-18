package dev.thiagosindra.cloudlug.database.state

import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.TransferItemId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheChunkStateMachineTest {

    private fun chunk(
        offset: Long = 0,
        length: Long = 8L * 1024 * 1024,
        status: CacheChunkStatus,
    ) = CacheChunkEntity(
        id = CacheChunkId("c1"),
        transferItemId = TransferItemId("i1"),
        offset = offset,
        length = length,
        localFilename = "00000000.chunk",
        status = status,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `the lifecycle of spec 15-3 is legal end to end`() {
        listOf(
            CacheChunkStatus.ALLOCATED,
            CacheChunkStatus.DOWNLOADING,
            CacheChunkStatus.READY,
            CacheChunkStatus.UPLOADING,
            CacheChunkStatus.ACKNOWLEDGED,
            CacheChunkStatus.DELETED,
        ).zipWithNext().forEach { (from, to) ->
            assertTrue(CacheChunkStateMachine.isLegal(from, to), "$from -> $to")
        }
    }

    @Test
    fun `a chunk cannot be uploaded before it is downloaded`() {
        assertFalse(CacheChunkStateMachine.isLegal(CacheChunkStatus.ALLOCATED, CacheChunkStatus.UPLOADING))
        assertFalse(CacheChunkStateMachine.isLegal(CacheChunkStatus.DOWNLOADING, CacheChunkStatus.UPLOADING))
        assertFalse(CacheChunkStateMachine.isLegal(CacheChunkStatus.READY, CacheChunkStatus.ACKNOWLEDGED))
    }

    @Test
    fun `a deleted chunk is final`() {
        assertEquals(emptySet(), CacheChunkStateMachine.allowedFrom(CacheChunkStatus.DELETED))
        assertFailsWith<IllegalChunkTransitionException> {
            CacheChunkStateMachine.require(CacheChunkStatus.DELETED, CacheChunkStatus.READY)
        }
    }

    @Test
    fun `failed attempts fall back so the same bytes can be retried`() {
        assertTrue(CacheChunkStateMachine.isLegal(CacheChunkStatus.DOWNLOADING, CacheChunkStatus.ALLOCATED))
        assertTrue(CacheChunkStateMachine.isLegal(CacheChunkStatus.UPLOADING, CacheChunkStatus.READY))
    }

    @Test
    fun `a chunk the destination has not acknowledged is never dropped`() {
        listOf(
            CacheChunkStatus.ALLOCATED,
            CacheChunkStatus.DOWNLOADING,
            CacheChunkStatus.READY,
        ).forEach {
            assertFalse(
                CacheChunkStateMachine.isSafeToDelete(chunk(status = it), acknowledgedUploadBytes = Long.MAX_VALUE),
                "$it must be retained (spec §32.4)",
            )
        }
    }

    @Test
    fun `a sent but unacknowledged chunk is retained`() {
        val sent = chunk(offset = 0, length = 100, status = CacheChunkStatus.UPLOADING)
        assertFalse(CacheChunkStateMachine.isSafeToDelete(sent, acknowledgedUploadBytes = 99))
        assertTrue(CacheChunkStateMachine.isSafeToDelete(sent, acknowledgedUploadBytes = 100))
    }

    @Test
    fun `an acknowledged chunk is droppable`() {
        assertTrue(
            CacheChunkStateMachine.isSafeToDelete(
                chunk(status = CacheChunkStatus.ACKNOWLEDGED),
                acknowledgedUploadBytes = 0,
            ),
        )
    }

    @Test
    fun `cancellation discards every chunk that still exists`() {
        CacheChunkStatus.entries.filter { it != CacheChunkStatus.DELETED }.forEach {
            assertTrue(CacheChunkStateMachine.isDiscardableOnCancellation(chunk(status = it)), "$it")
        }
        assertFalse(
            CacheChunkStateMachine.isDiscardableOnCancellation(chunk(status = CacheChunkStatus.DELETED)),
        )
    }
}
