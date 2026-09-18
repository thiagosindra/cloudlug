package dev.thiagosindra.cloudlug.database.state

import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Legal *and* illegal transitions, as spec §13 requires. The exhaustive tests
 * matter more than the happy-path ones: they are what stops a later change from
 * quietly making, say, COMPLETED -> DOWNLOADING possible.
 */
class StateMachineTest {

    @Test
    fun `the happy path of spec 13-1 is legal`() {
        val path = listOf(
            TransferStatus.DRAFT,
            TransferStatus.PREPARING,
            TransferStatus.READY,
            TransferStatus.RUNNING,
            TransferStatus.COMPLETED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(TransferStateMachine.isLegal(from, to), "$from -> $to should be legal")
        }
    }

    @Test
    fun `the happy path of spec 13-2 is legal`() {
        val path = listOf(
            TransferItemStatus.PENDING,
            TransferItemStatus.CHECKING_DESTINATION,
            TransferItemStatus.DOWNLOADING,
            TransferItemStatus.CACHED,
            TransferItemStatus.UPLOADING,
            TransferItemStatus.VERIFYING,
            TransferItemStatus.COMPLETED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(TransferItemStateMachine.isLegal(from, to), "$from -> $to should be legal")
        }
    }

    @Test
    fun `every transfer status declares its successors`() {
        TransferStatus.entries.forEach { status ->
            TransferStateMachine.allowedFrom(status) // throws if a status was forgotten
        }
    }

    @Test
    fun `every item status declares its successors`() {
        TransferItemStatus.entries.forEach { status ->
            TransferItemStateMachine.allowedFrom(status)
        }
    }

    @Test
    fun `completed transfers are final`() {
        assertEquals(emptySet(), TransferStateMachine.allowedFrom(TransferStatus.COMPLETED))
        assertFailsWith<IllegalTransferTransitionException> {
            TransferStateMachine.require(TransferStatus.COMPLETED, TransferStatus.RUNNING)
        }
    }

    @Test
    fun `a transfer cannot skip preparation or jump backwards into running`() {
        assertFalse(TransferStateMachine.isLegal(TransferStatus.DRAFT, TransferStatus.RUNNING))
        assertFalse(TransferStateMachine.isLegal(TransferStatus.DRAFT, TransferStatus.COMPLETED))
        assertFalse(TransferStateMachine.isLegal(TransferStatus.PREPARING, TransferStatus.RUNNING))
        assertFalse(TransferStateMachine.isLegal(TransferStatus.COMPLETED_WITH_ISSUES, TransferStatus.RUNNING))
    }

    @Test
    fun `waiting states resume into running and never into completion`() {
        listOf(
            TransferStatus.WAITING_FOR_WIFI,
            TransferStatus.WAITING_FOR_STORAGE,
            TransferStatus.AUTH_REQUIRED,
        ).forEach { waiting ->
            assertTrue(TransferStateMachine.isLegal(waiting, TransferStatus.RUNNING), "$waiting -> RUNNING")
            assertTrue(TransferStateMachine.isLegal(waiting, TransferStatus.CANCELLED), "$waiting -> CANCELLED")
            assertFalse(TransferStateMachine.isLegal(waiting, TransferStatus.COMPLETED), "$waiting -> COMPLETED")
        }
    }

    @Test
    fun `retry re-enters preparation from every unfinished terminal state`() {
        listOf(TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.COMPLETED_WITH_ISSUES)
            .forEach { assertTrue(TransferStateMachine.isLegal(it, TransferStatus.PREPARING), "$it -> PREPARING") }
        assertFalse(TransferStateMachine.isLegal(TransferStatus.COMPLETED, TransferStatus.PREPARING))
    }

    @Test
    fun `an item never uploads before it is cached`() {
        assertFalse(TransferItemStateMachine.isLegal(TransferItemStatus.PENDING, TransferItemStatus.UPLOADING))
        assertFalse(TransferItemStateMachine.isLegal(TransferItemStatus.DOWNLOADING, TransferItemStatus.UPLOADING))
        assertFalse(TransferItemStateMachine.isLegal(TransferItemStatus.CACHED, TransferItemStatus.VERIFYING))
    }

    @Test
    fun `an item never completes without verification`() {
        TransferItemStatus.entries
            .filter { it != TransferItemStatus.VERIFYING }
            .forEach {
                assertFalse(
                    TransferItemStateMachine.isLegal(it, TransferItemStatus.COMPLETED),
                    "$it -> COMPLETED must require VERIFYING first (spec §21, §32.3)",
                )
            }
    }

    @Test
    fun `completed and skipped items are final`() {
        listOf(
            TransferItemStatus.COMPLETED,
            TransferItemStatus.SKIPPED_DUPLICATE,
            TransferItemStatus.SKIPPED_UNSUPPORTED,
        ).forEach { assertEquals(emptySet(), TransferItemStateMachine.allowedFrom(it), "$it must be final") }
    }

    @Test
    fun `conflict source-changed failed and cancelled items can be retried`() {
        listOf(
            TransferItemStatus.CONFLICT,
            TransferItemStatus.SOURCE_CHANGED,
            TransferItemStatus.FAILED,
            TransferItemStatus.CANCELLED,
        ).forEach {
            assertEquals(setOf(TransferItemStatus.PENDING), TransferItemStateMachine.allowedFrom(it), "$it")
        }
    }

    @Test
    fun `every active item state can be cancelled`() {
        TransferItemStatus.entries.filter { it.isActive }.forEach {
            assertTrue(TransferItemStateMachine.isLegal(it, TransferItemStatus.CANCELLED), "$it -> CANCELLED")
        }
    }

    @Test
    fun `an item may only be created in a state that precedes work or settles it at manifest time`() {
        assertTrue(TransferItemStateMachine.isLegalInitialStatus(TransferItemStatus.PENDING))
        assertTrue(TransferItemStateMachine.isLegalInitialStatus(TransferItemStatus.SKIPPED_UNSUPPORTED))
        assertTrue(TransferItemStateMachine.isLegalInitialStatus(TransferItemStatus.CONFLICT))
        listOf(
            TransferItemStatus.DOWNLOADING,
            TransferItemStatus.COMPLETED,
            TransferItemStatus.SKIPPED_DUPLICATE,
            TransferItemStatus.VERIFYING,
        ).forEach { assertFalse(TransferItemStateMachine.isLegalInitialStatus(it), "$it") }
    }

    @Test
    fun `recovery re-queues interrupted work and leaves settled work alone`() {
        assertEquals(
            TransferItemStatus.PENDING,
            TransferItemStateMachine.recoveryStatusFor(TransferItemStatus.DOWNLOADING),
        )
        assertEquals(
            TransferItemStatus.PENDING,
            TransferItemStateMachine.recoveryStatusFor(TransferItemStatus.CHECKING_DESTINATION),
        )
        assertEquals(
            TransferItemStatus.CACHED,
            TransferItemStateMachine.recoveryStatusFor(TransferItemStatus.UPLOADING),
        )
        listOf(
            TransferItemStatus.PENDING,
            TransferItemStatus.CACHED,
            TransferItemStatus.VERIFYING,
            TransferItemStatus.COMPLETED,
            TransferItemStatus.CONFLICT,
        ).forEach { assertEquals(it, TransferItemStateMachine.recoveryStatusFor(it), "$it must be left alone") }
    }

    @Test
    fun `every recovery target is itself a legal transition`() {
        TransferItemStatus.entries.forEach { status ->
            val target = TransferItemStateMachine.recoveryStatusFor(status)
            if (target != status) {
                assertTrue(
                    TransferItemStateMachine.isLegal(status, target),
                    "recovery $status -> $target must be a legal transition",
                )
            }
        }
    }
}
