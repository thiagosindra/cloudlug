package dev.thiagosindra.cloudlug.database

import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.database.state.IllegalItemTransitionException
import dev.thiagosindra.cloudlug.database.state.IllegalTransferTransitionException
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferRepositoryTest {

    private val database = InMemoryCloudLugDatabase()
    private val repository = TransferRepository(database, TestFixtures.clock())

    private suspend fun seedTransfer(): TransferId {
        repository.createTransfer(TestFixtures.transfer())
        return TransferId("t1")
    }

    private suspend fun seedRunningTransfer(items: List<TransferItemEntity>): TransferId {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(id, items)
        repository.transitionTransfer(id, TransferStatus.READY)
        repository.transitionTransfer(id, TransferStatus.RUNNING)
        return id
    }

    /** Drives an item all the way to COMPLETED through the legal chain. */
    private suspend fun completeItem(id: String, destinationObjectId: String = "dst-$id") {
        val itemId = TransferItemId(id)
        repository.transitionItem(itemId, TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(itemId, TransferItemStatus.DOWNLOADING)
        repository.transitionItem(itemId, TransferItemStatus.CACHED)
        repository.transitionItem(itemId, TransferItemStatus.UPLOADING)
        repository.transitionItem(itemId, TransferItemStatus.VERIFYING) {
            it.copy(destinationObjectId = destinationObjectId)
        }
        repository.transitionItem(
            itemId,
            TransferItemStatus.COMPLETED,
            ItemStatusReason.VERIFIED_BY_DESTINATION_HASH,
        )
    }

    @Test
    fun `a transfer from an account to itself is rejected by the domain model`() {
        // §2.2 as amended: the pair must be two different accounts. This one is
        // a copy of a tree into its own subtree, which §2.3 cannot express and
        // which can recurse.
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.transfer(
                source = ProviderType.DROPBOX,
                destination = ProviderType.DROPBOX,
                sourceAccount = "the-same-account",
                destinationAccount = "the-same-account",
            )
        }
    }

    @Test
    fun `two accounts at the same provider are a legal pair`() {
        // What v0.4 exists to allow, and what the old provider-comparison rule
        // forbade: Dropbox to Dropbox between two accounts one person owns.
        // Until Drive lands this is the only transfer CloudLug can perform.
        val transfer = TestFixtures.transfer(
            source = ProviderType.DROPBOX,
            destination = ProviderType.DROPBOX,
            sourceAccount = "dbid:AAA",
            destinationAccount = "dbid:BBB",
        )

        assertEquals(transfer.sourceProvider, transfer.destinationProvider)
        assertTrue(transfer.sourceAccountId != transfer.destinationAccountId)
    }

    @Test
    fun `an illegal transfer transition is refused and nothing is written`() = runTest {
        val id = seedTransfer()
        assertFailsWith<IllegalTransferTransitionException> {
            repository.transitionTransfer(id, TransferStatus.RUNNING)
        }
        assertEquals(TransferStatus.DRAFT, repository.findTransfer(id)?.status)
    }

    @Test
    fun `manifest insertion updates totals in the same transaction`() = runTest {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(
                TestFixtures.item("i1", size = 100),
                TestFixtures.item("i2", size = 250),
                TestFixtures.item("f1", size = null, kind = CloudObjectType.FOLDER),
            ),
            cursor = "page-2",
        )

        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(2, transfer.totalFiles, "folders are not counted as files")
        assertEquals(350, transfer.totalBytes)
        assertEquals("page-2", transfer.enumerationCursor)
    }

    @Test
    fun `a restarted enumeration does not duplicate manifest rows`() = runTest {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(id, listOf(TestFixtures.item("i1", sourceObjectId = "src-a", size = 10)))

        // The adapter could not resume from the cursor and started again.
        val inserted = repository.appendManifestItems(
            id,
            listOf(
                TestFixtures.item("i1-again", sourceObjectId = "src-a", size = 10),
                TestFixtures.item("i2", sourceObjectId = "src-b", size = 20),
            ),
        )

        assertEquals(listOf(TransferItemId("i2")), inserted.map { it.id })
        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(2, transfer.totalFiles)
        assertEquals(30, transfer.totalBytes)
    }

    @Test
    fun `items classified at manifest time are counted immediately`() = runTest {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(
                TestFixtures.item("doc", size = null, kind = CloudObjectType.PROVIDER_NATIVE_DOCUMENT)
                    .copy(
                        status = TransferItemStatus.SKIPPED_UNSUPPORTED,
                        statusReason = ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
                    ),
                TestFixtures.item("clash", size = 5).copy(
                    status = TransferItemStatus.CONFLICT,
                    statusReason = ItemStatusReason.CONFLICT_CASE_INSENSITIVE_COLLISION,
                ),
            ),
        )

        val transfer = assertNotNull(repository.findTransfer(id))
        // v1.2 §12.1 splits what ADR-0008 pooled as skippedFiles: an
        // unsupported item is not a duplicate, and only duplicates count as
        // success in §13.1.
        assertEquals(1, transfer.unsupportedFiles)
        assertEquals(0, transfer.duplicateFiles)
        assertEquals(1, transfer.conflictFiles)
        assertEquals(2, transfer.settledFiles)
        // The native document reported no size (spec §11, §20.1).
        assertEquals(1, transfer.unknownSizeFiles)
        assertEquals(5, transfer.totalBytes)
        assertFalse(transfer.settledCleanly)
    }

    @Test
    fun `an item cannot be created mid-flight`() = runTest {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        assertFailsWith<IllegalStateException> {
            repository.appendManifestItems(
                id,
                listOf(TestFixtures.item("i1", status = TransferItemStatus.DOWNLOADING)),
            )
        }
        assertEquals(emptyList(), repository.listItems(id))
    }

    @Test
    fun `completing an item moves the file and byte counters together`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 1_500)))
        completeItem("i1")

        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(1, transfer.completedFiles)
        assertEquals(1_500, transfer.completedBytes)
        assertEquals(TransferItemStatus.COMPLETED, repository.findItem(TransferItemId("i1"))?.status)
    }

    @Test
    fun `retrying a completed-with-errors item moves counters back`() = runTest {
        val id = seedRunningTransfer(
            listOf(TestFixtures.item("i1", size = 100), TestFixtures.item("i2", size = 200)),
        )
        completeItem("i1")
        repository.transitionItem(TransferItemId("i2"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(
            TransferItemId("i2"),
            TransferItemStatus.CONFLICT,
            ItemStatusReason.CONFLICT_SIZE_DIFFERS,
        )

        var transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(1, transfer.conflictFiles)

        // The user removed the destination object and retried (spec §13.2).
        repository.transitionItem(TransferItemId("i2"), TransferItemStatus.PENDING)

        transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(0, transfer.conflictFiles)
        assertEquals(1, transfer.completedFiles, "the completed item is untouched")
        assertEquals(100, transfer.completedBytes)
    }

    @Test
    fun `an illegal item transition leaves both the item and the counters untouched`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100)))
        assertFailsWith<IllegalItemTransitionException> {
            repository.transitionItem(TransferItemId("i1"), TransferItemStatus.COMPLETED)
        }

        assertEquals(TransferItemStatus.PENDING, repository.findItem(TransferItemId("i1"))?.status)
        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(0, transfer.completedFiles)
        assertEquals(0, transfer.completedBytes)
    }

    @Test
    fun `a failure inside a transaction rolls every table back`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100)))
        val before = assertNotNull(repository.findTransfer(id))

        assertFailsWith<IllegalStateException> {
            database.withTransaction {
                database.transfers.update(before.copy(completedFiles = 99, completedBytes = 999))
                database.items.insertAll(listOf(TestFixtures.item("i2", size = 5)))
                error("provider blew up halfway through")
            }
        }

        assertEquals(before, repository.findTransfer(id))
        assertEquals(listOf(TransferItemId("i1")), repository.listItems(id).map { it.id })
    }

    @Test
    fun `a transfer with only skipped items completes cleanly`() = runTest {
        val id = seedRunningTransfer(
            listOf(TestFixtures.item("i1", size = 100), TestFixtures.item("i2", size = 100)),
        )
        completeItem("i1")
        repository.transitionItem(TransferItemId("i2"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(
            TransferItemId("i2"),
            TransferItemStatus.SKIPPED_DUPLICATE,
            ItemStatusReason.DUPLICATE_VERIFIED_BY_HASH,
        )

        assertEquals(TransferStatus.COMPLETED, repository.finishTransfer(id).status)
    }

    @Test
    fun `a transfer with a failure completes with errors`() = runTest {
        val id = seedRunningTransfer(
            listOf(TestFixtures.item("i1", size = 100), TestFixtures.item("i2", size = 100)),
        )
        completeItem("i1")
        repository.transitionItem(TransferItemId("i2"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(
            TransferItemId("i2"),
            TransferItemStatus.FAILED,
            ItemStatusReason.ERROR_PERMANENT,
        )

        assertEquals(TransferStatus.COMPLETED_WITH_ISSUES, repository.finishTransfer(id).status)
    }

    @Test
    fun `a transfer cannot finish while items are still unsettled`() = runTest {
        val id = seedRunningTransfer(
            listOf(TestFixtures.item("i1", size = 100), TestFixtures.item("i2", size = 100)),
        )
        completeItem("i1")
        assertFailsWith<IllegalStateException> { repository.finishTransfer(id) }
        assertEquals(TransferStatus.RUNNING, repository.findTransfer(id)?.status)
    }

    @Test
    fun `recovery re-queues items that were mid-flight when the process died`() = runTest {
        val id = seedRunningTransfer(
            listOf(
                TestFixtures.item("downloading", size = 100),
                TestFixtures.item("uploading", size = 100),
                TestFixtures.item("done", size = 100),
            ),
        )
        repository.transitionItem(TransferItemId("downloading"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(TransferItemId("downloading"), TransferItemStatus.DOWNLOADING)
        repository.transitionItem(TransferItemId("uploading"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(TransferItemId("uploading"), TransferItemStatus.DOWNLOADING)
        repository.transitionItem(TransferItemId("uploading"), TransferItemStatus.CACHED)
        repository.transitionItem(TransferItemId("uploading"), TransferItemStatus.UPLOADING)
        completeItem("done")

        assertEquals(2, repository.recoverInterruptedItems(id))
        assertEquals(
            TransferItemStatus.PENDING,
            repository.findItem(TransferItemId("downloading"))?.status,
        )
        assertEquals(TransferItemStatus.CACHED, repository.findItem(TransferItemId("uploading"))?.status)
        assertEquals(TransferItemStatus.COMPLETED, repository.findItem(TransferItemId("done"))?.status)
        assertEquals(1, repository.findTransfer(id)?.completedFiles)
    }

    @Test
    fun `recovery is idempotent`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100)))
        repository.transitionItem(TransferItemId("i1"), TransferItemStatus.CHECKING_DESTINATION)

        assertEquals(1, repository.recoverInterruptedItems(id))
        assertEquals(0, repository.recoverInterruptedItems(id))
    }

    @Test
    fun `a completed item is found again as an idempotency record`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100, revision = "rev-7")))
        completeItem("i1", destinationObjectId = "dst-1")

        val transfer = assertNotNull(repository.findTransfer(id))
        val record = repository.findPriorTransferOf(
            sourceAccountId = transfer.sourceAccountId,
            sourceObjectId = "src-i1",
            sourceRevision = "rev-7",
            size = 100,
        )
        assertEquals("dst-1", assertNotNull(record).destinationObjectId)
    }

    @Test
    fun `an idempotency record does not match a changed revision or size`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100, revision = "rev-7")))
        completeItem("i1")
        val transfer = assertNotNull(repository.findTransfer(id))

        assertNull(
            repository.findPriorTransferOf(transfer.sourceAccountId, "src-i1", "rev-8", 100),
            "a new revision is not the object we transferred (spec §20.6)",
        )
        assertNull(repository.findPriorTransferOf(transfer.sourceAccountId, "src-i1", "rev-7", 101))
    }

    @Test
    fun `an unfinished item is never an idempotency record`() = runTest {
        val id = seedRunningTransfer(listOf(TestFixtures.item("i1", size = 100)))
        repository.transitionItem(TransferItemId("i1"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(TransferItemId("i1"), TransferItemStatus.DOWNLOADING)
        val transfer = assertNotNull(repository.findTransfer(id))

        assertNull(repository.findPriorTransferOf(transfer.sourceAccountId, "src-i1", "rev-1", 100))
    }

    @Test
    fun `progress and session state are persisted without changing item state`() = runTest {
        seedRunningTransfer(listOf(TestFixtures.item("i1", size = 800)))
        val itemId = TransferItemId("i1")
        repository.transitionItem(itemId, TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(itemId, TransferItemStatus.DOWNLOADING)

        repository.recordDownloadProgress(itemId, 512)
        repository.recordUploadProgress(itemId, 256)
        repository.recordUploadSession(
            itemId,
            sessionId = "session-1",
            metadata = """{"uri":"opaque"}""",
            expiresAt = TestFixtures.EPOCH.plusSeconds(604_800),
        )
        repository.recordRetryAttempt(itemId, errorCode = "429", errorMessage = "throttled")

        val item = assertNotNull(repository.findItem(itemId))
        assertEquals(TransferItemStatus.DOWNLOADING, item.status)
        assertEquals(512, item.downloadedBytes)
        assertEquals(256, item.uploadedBytes)
        assertEquals("session-1", item.uploadSessionId)
        assertEquals(1, item.retryCount)
        assertTrue(item.uploadSessionExpiresAt!!.isAfter(TestFixtures.EPOCH))
    }
}
