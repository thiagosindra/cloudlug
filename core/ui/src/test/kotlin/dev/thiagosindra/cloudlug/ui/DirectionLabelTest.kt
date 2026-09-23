package dev.thiagosindra.cloudlug.ui

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What §24.1 and §24.3 call a transfer.
 *
 * The case that matters is two accounts at one provider, which §2.2 as amended
 * permits and v0.4 shipped: before this, every Dropbox → Dropbox transfer in
 * the list read "Dropbox -> Dropbox", including the reverse of the one above
 * it.
 */
class DirectionLabelTest {

    @Test
    fun `two providers need no addresses to tell them apart`() {
        val transfer = transfer(ProviderType.DROPBOX, ProviderType.GOOGLE_DRIVE)

        // Unchanged, deliberately: the emulator journey asserts this exact
        // string, and an address here would only make the row longer.
        assertEquals("Dropbox -> Google Drive", transfer.directionLabel(NAMES))
    }

    @Test
    fun `two accounts at one provider are named by account`() {
        val transfer = transfer(ProviderType.DROPBOX, ProviderType.DROPBOX)

        assertEquals(
            "Dropbox (a@example.invalid) -> Dropbox (b@example.invalid)",
            transfer.directionLabel(NAMES),
        )
    }

    @Test
    fun `the reverse transfer reads differently from the forward one`() {
        val forward = transfer(ProviderType.DROPBOX, ProviderType.DROPBOX)
        val back = forward.copy(
            sourceAccountId = forward.destinationAccountId,
            destinationAccountId = forward.sourceAccountId,
        )

        // The whole point: two rows in §24.1's list that are different
        // transfers must not read identically.
        assertNotEquals(forward.directionLabel(NAMES), back.directionLabel(NAMES))
    }

    @Test
    fun `a disconnected account still distinguishes its end`() {
        val transfer = transfer(ProviderType.DROPBOX, ProviderType.DROPBOX)

        // §8.3 removes the account row on disconnect, but the transfers it ran
        // stay in the history. Falling back to the id keeps the two ends
        // distinguishable, which is what this label is for.
        assertEquals(
            "Dropbox (dbid:one) -> Dropbox (dbid:two)",
            transfer.directionLabel(emptyMap()),
        )
    }

    private fun transfer(source: ProviderType, destination: ProviderType) = TransferEntity(
        id = TransferId("t1"),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sourceProvider = source,
        sourceAccountId = AccountId("dbid:one"),
        destinationProvider = destination,
        destinationAccountId = AccountId("dbid:two"),
        destinationRootId = "destination-root",
        destinationContainerName = "CloudLug - 2026-09-23 00-00",
    )

    private companion object {
        val NAMES = mapOf(
            AccountId("dbid:one") to "a@example.invalid",
            AccountId("dbid:two") to "b@example.invalid",
        )
    }
}
