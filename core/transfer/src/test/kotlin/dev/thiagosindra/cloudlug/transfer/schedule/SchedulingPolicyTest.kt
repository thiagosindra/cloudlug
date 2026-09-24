package dev.thiagosindra.cloudlug.transfer.schedule

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which transfers come back after the process dies, and what the platform is
 * told to wait for.
 *
 * On the JVM, because it is a decision about persisted state and nothing else.
 * The two schedulers that act on it need a device; the rule they share does
 * not, and a device is the worst place to discover that a resumed transfer was
 * quietly dropped.
 */
class SchedulingPolicyTest {

    @Test
    fun `every non-terminal status except draft and paused comes back`() {
        // Stated as a partition of the whole enum rather than a list of
        // examples: a status added later lands in one of these sets on purpose,
        // and the exhaustive `when` in the policy plus this assertion together
        // make that choice deliberate rather than accidental.
        val enqueueable = TransferStatus.entries.filter { SchedulingPolicy.isEnqueueable(transfer(it)) }

        assertEquals(
            setOf(
                TransferStatus.PREPARING,
                TransferStatus.READY,
                TransferStatus.RUNNING,
                TransferStatus.WAITING_FOR_WIFI,
                TransferStatus.WAITING_FOR_STORAGE,
                TransferStatus.AUTH_REQUIRED,
            ),
            enqueueable.toSet(),
        )
    }

    @Test
    fun `a paused transfer is the user's decision and is not restarted`() {
        // §22.1. A scheduler that re-enqueued PAUSED would undo a button press
        // every time the process restarted, which is the one behaviour a user
        // would read as the app ignoring them.
        assertFalse(SchedulingPolicy.isEnqueueable(transfer(TransferStatus.PAUSED)))
    }

    @Test
    fun `a transfer waiting for Wi-Fi is still the platform's to watch`() {
        // It is not idle: the network constraint is exactly what a scheduler is
        // for, and dropping it here would mean a transfer that never woke when
        // Wi-Fi came back.
        assertTrue(SchedulingPolicy.isEnqueueable(transfer(TransferStatus.WAITING_FOR_WIFI)))
    }

    @Test
    fun `a finished transfer is never enqueued again`() {
        TransferStatus.entries.filter { it.isTerminal }.forEach {
            assertFalse(SchedulingPolicy.isEnqueueable(transfer(it)), "$it is terminal")
        }
    }

    @Test
    fun `an explicit start can lift a pause, but the sweep cannot`() {
        val paused = transfer(TransferStatus.PAUSED)

        // The two rules differ on exactly this status, and that is the point:
        // Resume is a button, and §22.1's pause stands only until it is
        // pressed. If the sweep used the same rule, every process restart
        // would undo a pause; if Resume used the sweep's, the button would do
        // nothing.
        assertTrue(SchedulingPolicy.isStartable(paused))
        assertFalse(SchedulingPolicy.isEnqueueable(paused))
    }

    @Test
    fun `neither rule will start a draft or a finished transfer`() {
        val never = TransferStatus.entries.filter { it.isTerminal } + TransferStatus.DRAFT
        never.forEach {
            assertFalse(SchedulingPolicy.isStartable(transfer(it)), "$it must not be startable")
            assertFalse(SchedulingPolicy.isEnqueueable(transfer(it)), "$it must not be enqueueable")
        }
    }

    @Test
    fun `the default policy demands an unmetered network`() {
        // §16: cellular requires explicit opt-in, and "never silently fall back"
        // is the invariant the platform constraint backs up.
        assertEquals(
            NetworkRequirement.UNMETERED,
            SchedulingPolicy.networkFor(transfer(TransferStatus.READY, TransferNetworkPolicy.UNMETERED_ONLY)),
        )
    }

    @Test
    fun `an opted-in transfer accepts any connection`() {
        assertEquals(
            NetworkRequirement.ANY_CONNECTED,
            SchedulingPolicy.networkFor(transfer(TransferStatus.READY, TransferNetworkPolicy.ANY_NETWORK)),
        )
    }

    @Test
    fun `reconciliation keeps only the transfers with work left`() {
        val all = listOf(
            transfer(TransferStatus.RUNNING, id = "running"),
            transfer(TransferStatus.PAUSED, id = "paused"),
            transfer(TransferStatus.COMPLETED, id = "done"),
            transfer(TransferStatus.WAITING_FOR_WIFI, id = "waiting"),
        )

        assertEquals(listOf("running", "waiting"), SchedulingPolicy.toEnqueue(all).map { it.id.value })
    }

    private fun transfer(
        status: TransferStatus,
        policy: TransferNetworkPolicy = TransferNetworkPolicy.UNMETERED_ONLY,
        id: String = "t1",
    ) = TransferEntity(
        id = TransferId(id),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sourceProvider = ProviderType.DROPBOX,
        sourceAccountId = AccountId("a"),
        destinationProvider = ProviderType.DROPBOX,
        destinationAccountId = AccountId("b"),
        destinationRootId = "root",
        destinationContainerName = "CloudLug - 2026-09-24 00-00",
        networkPolicy = policy,
        status = status,
    )
}
