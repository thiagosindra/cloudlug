package dev.thiagosindra.cloudlug.database

import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §13.1: a transfer ends COMPLETED only if every item ended COMPLETED or
 * SKIPPED_DUPLICATE. This overrules ADR-0008, under which skipped items alone
 * left a transfer COMPLETED.
 */
class CompletionRuleTest {

    private val database = InMemoryCloudLugDatabase()
    private val repository = TransferRepository(database, TestFixtures.clock())

    private var seeded = 0

    private suspend fun seed(): TransferId {
        val id = "t${++seeded}"
        repository.createTransfer(TestFixtures.transfer(id = id))
        return TransferId(id)
    }

    /** The legal §13.2 chain from PENDING to [status]. */
    private fun pathTo(status: TransferItemStatus): List<TransferItemStatus> = when (status) {
        TransferItemStatus.COMPLETED -> listOf(
            TransferItemStatus.CHECKING_DESTINATION,
            TransferItemStatus.DOWNLOADING,
            TransferItemStatus.CACHED,
            TransferItemStatus.UPLOADING,
            TransferItemStatus.VERIFYING,
            TransferItemStatus.COMPLETED,
        )

        else -> listOf(TransferItemStatus.CHECKING_DESTINATION, status)
    }

    private suspend fun transferEndingWith(
        status: TransferItemStatus,
        reason: ItemStatusReason?,
    ): TransferStatus {
        val id = seed()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(TestFixtures.item("a-$seeded", transferId = id.value, size = 10)),
        )
        repository.transitionTransfer(id, TransferStatus.READY)
        repository.transitionTransfer(id, TransferStatus.RUNNING)
        val item = repository.listItems(id).single()
        for (step in pathTo(status)) {
            repository.transitionItem(item.id, step, if (step == status) reason else null)
        }
        return repository.finishTransfer(id).status
    }

    private fun chunkFor(itemId: TransferItemId) = CacheChunkEntity(
        id = CacheChunkId("c1"),
        transferItemId = itemId,
        offset = 0,
        length = 10,
        localFilename = "00000000.chunk",
        createdAt = TestFixtures.EPOCH,
    )

    @Test
    fun `only completed and duplicate items leave a transfer COMPLETED`() = runTest {
        assertEquals(
            TransferStatus.COMPLETED,
            transferEndingWith(TransferItemStatus.COMPLETED, ItemStatusReason.VERIFIED_BY_DESTINATION_HASH),
        )
        assertEquals(
            TransferStatus.COMPLETED,
            transferEndingWith(TransferItemStatus.SKIPPED_DUPLICATE, ItemStatusReason.ALREADY_TRANSFERRED),
        )
    }

    @Test
    fun `an unsupported item alone makes the transfer COMPLETED_WITH_ISSUES`() = runTest {
        // The case ADR-0008 got wrong: a skipped Google-native document is not
        // an error, but the transfer still did not move what the user selected.
        assertEquals(
            TransferStatus.COMPLETED_WITH_ISSUES,
            transferEndingWith(
                TransferItemStatus.SKIPPED_UNSUPPORTED,
                ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
            ),
        )
    }

    @Test
    fun `a source-changed item alone makes the transfer COMPLETED_WITH_ISSUES`() = runTest {
        assertEquals(
            TransferStatus.COMPLETED_WITH_ISSUES,
            transferEndingWith(
                TransferItemStatus.SOURCE_CHANGED,
                ItemStatusReason.SOURCE_REVISION_CHANGED,
            ),
        )
    }

    @Test
    fun `conflicted, failed and cancelled items each make it COMPLETED_WITH_ISSUES`() = runTest {
        assertEquals(
            TransferStatus.COMPLETED_WITH_ISSUES,
            transferEndingWith(TransferItemStatus.CONFLICT, ItemStatusReason.CONFLICT_SIZE_DIFFERS),
        )
        assertEquals(
            TransferStatus.COMPLETED_WITH_ISSUES,
            transferEndingWith(TransferItemStatus.FAILED, ItemStatusReason.ERROR_PERMANENT),
        )
        assertEquals(
            TransferStatus.COMPLETED_WITH_ISSUES,
            transferEndingWith(TransferItemStatus.CANCELLED, ItemStatusReason.CANCELLED_BY_USER),
        )
    }

    @Test
    fun `each outcome lands in its own counter`() = runTest {
        val id = seed()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(
                TestFixtures.item("dup", transferId = id.value, size = 1),
                TestFixtures.item(
                    "doc",
                    transferId = id.value,
                    size = null,
                    kind = CloudObjectType.PROVIDER_NATIVE_DOCUMENT,
                ).copy(
                    status = TransferItemStatus.SKIPPED_UNSUPPORTED,
                    statusReason = ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
                ),
                TestFixtures.item("clash", transferId = id.value, size = 2).copy(
                    status = TransferItemStatus.CONFLICT,
                    statusReason = ItemStatusReason.CONFLICT_ILLEGAL_NAME,
                ),
            ),
        )
        // A duplicate is discovered by the engine (§19.2, §19.3), not declared
        // at manifest time, so it is transitioned rather than created settled.
        repository.transitionTransfer(id, TransferStatus.READY)
        repository.transitionTransfer(id, TransferStatus.RUNNING)
        repository.transitionItem(TransferItemId("dup"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(
            TransferItemId("dup"),
            TransferItemStatus.SKIPPED_DUPLICATE,
            ItemStatusReason.ALREADY_TRANSFERRED,
        )

        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(1, transfer.duplicateFiles)
        assertEquals(1, transfer.unsupportedFiles)
        assertEquals(1, transfer.conflictFiles)
        assertEquals(0, transfer.sourceChangedFiles)
        assertEquals(3, transfer.settledFiles)
    }

    /** Spec §11: totalBytes counts known sizes; the rest are counted separately. */
    @Test
    fun `a size becoming known moves it from unknownSizeFiles into totalBytes`() = runTest {
        val id = seed()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(
                TestFixtures.item(
                    "doc",
                    transferId = id.value,
                    size = null,
                    kind = CloudObjectType.PROVIDER_NATIVE_DOCUMENT,
                ),
                TestFixtures.item("known", transferId = id.value, size = 400),
            ),
        )

        val before = assertNotNull(repository.findTransfer(id))
        assertEquals(400, before.totalBytes)
        assertEquals(1, before.unknownSizeFiles)
        assertTrue(before.hasUnknownSizes)

        val doc = repository.listItems(id).first { it.size == null }
        repository.recordItemSize(doc.id, 1_024)

        val after = assertNotNull(repository.findTransfer(id))
        assertEquals(1_424, after.totalBytes)
        assertEquals(0, after.unknownSizeFiles)
        assertTrue(!after.hasUnknownSizes)
    }

    /** Spec §15.3: the acknowledgment and the checkpoint share one transaction. */
    @Test
    fun `acknowledging a chunk stores the hash checkpoint atomically`() = runTest {
        val id = seed()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(TestFixtures.item("a-$seeded", transferId = id.value, size = 10)),
        )
        val item = repository.listItems(id).single()
        assertNull(item.hashCheckpoint)

        val chunk = repository.insertCacheChunk(chunkFor(item.id))
        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.DOWNLOADING)
        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.READY)
        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.UPLOADING)

        val checkpoint = HashCheckpoint("AQAGc2hhMjU2")
        repository.acknowledgeCacheChunk(chunk.id, checkpoint)

        assertEquals(
            CacheChunkStatus.ACKNOWLEDGED,
            assertNotNull(repository.listCacheChunks(item.id).singleOrNull()).status,
        )
        assertEquals(checkpoint, assertNotNull(repository.findItem(item.id)).hashCheckpoint)
    }

    /** An illegal chunk transition must roll back the checkpoint write too. */
    @Test
    fun `a rejected acknowledgment leaves both the chunk and the checkpoint untouched`() = runTest {
        val id = seed()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(
            id,
            listOf(TestFixtures.item("a-$seeded", transferId = id.value, size = 10)),
        )
        val item = repository.listItems(id).single()
        val chunk = repository.insertCacheChunk(chunkFor(item.id))

        // ALLOCATED -> ACKNOWLEDGED is not a legal edge (spec §15.3).
        val failed = runCatching {
            repository.acknowledgeCacheChunk(chunk.id, HashCheckpoint("AQAGc2hhMjU2"))
        }
        assertTrue(failed.isFailure)
        assertEquals(
            CacheChunkStatus.ALLOCATED,
            assertNotNull(repository.listCacheChunks(item.id).singleOrNull()).status,
        )
        assertNull(assertNotNull(repository.findItem(item.id)).hashCheckpoint)
    }
}
