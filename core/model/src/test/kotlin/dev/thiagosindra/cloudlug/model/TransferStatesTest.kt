package dev.thiagosindra.cloudlug.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferStatesTest {

    @Test
    fun `every item state is either terminal or not active or active`() {
        TransferItemStatus.entries.forEach { status ->
            if (status.isTerminal) assertFalse(status.isActive, "$status cannot be terminal and active")
        }
        assertTrue(TransferItemStatus.DOWNLOADING.isActive)
        assertFalse(TransferItemStatus.PENDING.isActive)
    }

    @Test
    fun `spec 13-2 outcomes are all terminal`() {
        listOf(
            TransferItemStatus.COMPLETED,
            TransferItemStatus.SKIPPED_DUPLICATE,
            TransferItemStatus.SKIPPED_UNSUPPORTED,
            TransferItemStatus.CONFLICT,
            TransferItemStatus.SOURCE_CHANGED,
            TransferItemStatus.FAILED,
            TransferItemStatus.CANCELLED,
        ).forEach { assertTrue(it.isTerminal, "$it must be terminal") }
    }

    @Test
    fun `transfer waiting states are not terminal`() {
        TransferStatus.entries.filter { it.isWaiting }.forEach { assertFalse(it.isTerminal) }
        assertEquals(
            setOf(
                TransferStatus.WAITING_FOR_WIFI,
                TransferStatus.WAITING_FOR_STORAGE,
                TransferStatus.AUTH_REQUIRED,
            ),
            TransferStatus.entries.filter { it.isWaiting }.toSet(),
        )
    }
}
