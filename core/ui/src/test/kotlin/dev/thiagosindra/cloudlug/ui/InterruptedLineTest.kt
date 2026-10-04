package dev.thiagosindra.cloudlug.ui

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * §24.3 says "Running" only while something is.
 *
 * After the v0.6.1 `OutOfMemoryError` the row still said RUNNING, and so did
 * the screen, for a transfer nothing was moving.
 */
class InterruptedLineTest {

    @Test
    fun `a RUNNING row with a worker is running`() {
        assertEquals("Running", transfer(TransferStatus.RUNNING).summaryLine(owned = true))
    }

    @Test
    fun `a RUNNING row with no worker is interrupted, not running`() {
        assertEquals("Interrupted — resuming", transfer(TransferStatus.RUNNING).summaryLine(owned = false))
    }

    @Test
    fun `when the platform says why it is holding the job, the line says so`() {
        assertEquals(
            "Interrupted — held by Android: battery saver or the phone's state",
            transfer(TransferStatus.RUNNING).summaryLine(
                owned = false,
                heldBecause = "held by Android: battery saver or the phone's state",
            ),
        )
    }

    @Test
    fun `any other status reads as it always did`() {
        TransferStatus.entries.filter { it != TransferStatus.RUNNING }.forEach { status ->
            val transfer = transfer(status)
            assertEquals(transfer.summaryLine(), transfer.summaryLine(owned = false, heldBecause = "x"), "$status")
        }
    }

    private fun transfer(status: TransferStatus) = TransferEntity(
        id = TransferId("t"),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sourceProvider = ProviderType.DROPBOX,
        sourceAccountId = AccountId("a"),
        destinationProvider = ProviderType.GOOGLE_DRIVE,
        destinationAccountId = AccountId("b"),
        destinationRootId = "root",
        destinationContainerName = "CloudLug - 2026-10-04 00-00",
        status = status,
    )
}
