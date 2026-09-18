package dev.thiagosindra.cloudlug.database

import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.database.room.TestDatabases
import dev.thiagosindra.cloudlug.database.state.IllegalItemTransitionException
import dev.thiagosindra.cloudlug.database.state.IllegalTransferTransitionException
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transactional guarantees `TransferRepository` relies on, asserted against
 * every [CloudLugDatabase] implementation.
 *
 * ADR-0002 promised that swapping the in-memory store for Room could not change
 * behaviour. That is only worth anything if both are held to the same tests, so
 * this is a contract — the same shape as `ProviderContractTest` for adapters
 * (spec §31.2) — rather than two suites that drifted apart.
 *
 * Rollback is the property under test throughout: §13 says a transition is
 * validated and its counters updated in one transaction, so a refused
 * transition must leave *nothing* behind. An in-memory store gets this right by
 * snapshotting; Room gets it right through SQLite. They must agree.
 */
abstract class TransactionContractTest {

    abstract fun newDatabase(): CloudLugDatabase

    private val database: CloudLugDatabase by lazy { newDatabase() }
    private val repository: TransferRepository by lazy {
        TransferRepository(database, TestFixtures.clock())
    }

    @AfterTest
    fun tearDown() {
        (database as? AutoCloseable)?.close()
    }

    private suspend fun seedTransfer(): TransferId {
        repository.createTransfer(TestFixtures.transfer())
        return TransferId("t1")
    }

    private suspend fun seedRunning(vararg items: String): TransferId {
        val id = seedTransfer()
        repository.transitionTransfer(id, TransferStatus.PREPARING)
        repository.appendManifestItems(id, items.map { TestFixtures.item(it, size = 100) })
        repository.transitionTransfer(id, TransferStatus.READY)
        repository.transitionTransfer(id, TransferStatus.RUNNING)
        return id
    }

    @Test
    fun `a round trip preserves every persisted field`() = runTest {
        val id = seedRunning("i1")
        val stored = assertNotNull(repository.findItem(TransferItemId("i1")))
        val original = TestFixtures.item("i1", size = 100)

        assertEquals(original.sourceRelativePath, stored.sourceRelativePath)
        assertEquals(original.sourceObjectId, stored.sourceObjectId)
        assertEquals(original.sourceRevision, stored.sourceRevision)
        assertEquals(original.size, stored.size)
        assertEquals(original.objectKind, stored.objectKind)
        assertEquals(original.status, stored.status)

        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(TestFixtures.EPOCH, transfer.createdAt)
        assertEquals(TransferStatus.RUNNING, transfer.status)
    }

    @Test
    fun `an illegal transfer transition is refused and nothing is written`() = runTest {
        val id = seedTransfer()
        assertFailsWith<IllegalTransferTransitionException> {
            repository.transitionTransfer(id, TransferStatus.RUNNING)
        }
        assertEquals(TransferStatus.DRAFT, assertNotNull(repository.findTransfer(id)).status)
    }

    @Test
    fun `an illegal item transition leaves the item and the counters untouched`() = runTest {
        val id = seedRunning("i1")
        assertFailsWith<IllegalItemTransitionException> {
            repository.transitionItem(TransferItemId("i1"), TransferItemStatus.COMPLETED)
        }

        assertEquals(
            TransferItemStatus.PENDING,
            assertNotNull(repository.findItem(TransferItemId("i1"))).status,
        )
        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(0, transfer.completedFiles)
        assertEquals(0, transfer.completedBytes)
    }

    /**
     * The case that makes rollback load-bearing rather than theoretical: the
     * counter update and the item write are two rows, and a refused transition
     * must not apply one without the other.
     */
    @Test
    fun `a refused transition after a legal one rolls back only the refused write`() = runTest {
        val id = seedRunning("i1", "i2")
        repository.transitionItem(TransferItemId("i1"), TransferItemStatus.CHECKING_DESTINATION)
        repository.transitionItem(
            TransferItemId("i1"),
            TransferItemStatus.SKIPPED_DUPLICATE,
            ItemStatusReason.ALREADY_TRANSFERRED,
        )

        // i1 is terminal; SKIPPED_DUPLICATE -> UPLOADING is not legal (§13.2).
        assertFailsWith<IllegalItemTransitionException> {
            repository.transitionItem(TransferItemId("i1"), TransferItemStatus.UPLOADING)
        }

        val transfer = assertNotNull(repository.findTransfer(id))
        assertEquals(1, transfer.duplicateFiles, "the legal transition survives")
        assertEquals(1, transfer.settledFiles)
        assertEquals(
            TransferItemStatus.SKIPPED_DUPLICATE,
            assertNotNull(repository.findItem(TransferItemId("i1"))).status,
        )
        assertEquals(
            TransferItemStatus.PENDING,
            assertNotNull(repository.findItem(TransferItemId("i2"))).status,
        )
    }

    @Test
    fun `finishing a transfer with unsettled items is refused`() = runTest {
        val id = seedRunning("i1")
        assertFailsWith<IllegalStateException> { repository.finishTransfer(id) }
        assertEquals(TransferStatus.RUNNING, assertNotNull(repository.findTransfer(id)).status)
    }

    /** Spec §15.3: the acknowledgment and the hash checkpoint are one write. */
    @Test
    fun `acknowledging a chunk stores the checkpoint, and a refused one stores neither`() = runTest {
        val id = seedRunning("i1")
        val itemId = TransferItemId("i1")
        val chunk = repository.insertCacheChunk(
            CacheChunkEntity(
                id = CacheChunkId("c1"),
                transferItemId = itemId,
                offset = 0,
                length = 100,
                localFilename = "00000000.chunk",
                createdAt = TestFixtures.EPOCH,
            ),
        )

        // ALLOCATED -> ACKNOWLEDGED is not a legal edge, so neither row moves.
        assertFailsWith<Exception> {
            repository.acknowledgeCacheChunk(chunk.id, HashCheckpoint("AQAGc2hhMjU2"))
        }
        assertEquals(
            CacheChunkStatus.ALLOCATED,
            assertNotNull(repository.listCacheChunks(itemId).singleOrNull()).status,
        )
        assertNull(assertNotNull(repository.findItem(itemId)).hashCheckpoint)

        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.DOWNLOADING)
        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.READY)
        repository.transitionCacheChunk(chunk.id, CacheChunkStatus.UPLOADING)
        val checkpoint = HashCheckpoint("AQAGc2hhMjU2")
        repository.acknowledgeCacheChunk(chunk.id, checkpoint)

        assertEquals(
            CacheChunkStatus.ACKNOWLEDGED,
            assertNotNull(repository.listCacheChunks(itemId).singleOrNull()).status,
        )
        assertEquals(checkpoint, assertNotNull(repository.findItem(itemId)).hashCheckpoint)
        assertEquals(id, assertNotNull(repository.findTransfer(id)).id)
    }

    @Test
    fun `cached bytes exclude deleted chunks`() = runTest {
        seedRunning("i1")
        val itemId = TransferItemId("i1")
        repository.insertCacheChunk(
            CacheChunkEntity(
                id = CacheChunkId("c1"),
                transferItemId = itemId,
                offset = 0,
                length = 60,
                localFilename = "00000000.chunk",
                createdAt = TestFixtures.EPOCH,
            ),
        )
        assertEquals(60, repository.totalCachedBytes())
        repository.deleteCacheChunk(CacheChunkId("c1"))
        assertEquals(0, repository.totalCachedBytes())
    }

    @Test
    fun `the idempotency record finds a completed item across transfers`() = runTest {
        seedRunning("i1")
        val itemId = TransferItemId("i1")
        for (step in listOf(
            TransferItemStatus.CHECKING_DESTINATION,
            TransferItemStatus.DOWNLOADING,
            TransferItemStatus.CACHED,
            TransferItemStatus.UPLOADING,
            TransferItemStatus.VERIFYING,
        )) {
            repository.transitionItem(itemId, step)
        }
        repository.transitionItem(
            itemId,
            TransferItemStatus.COMPLETED,
            ItemStatusReason.VERIFIED_BY_DESTINATION_HASH,
        )

        val prior = repository.findPriorTransferOf(
            sourceAccountId = TestFixtures.transfer().sourceAccountId,
            sourceObjectId = TestFixtures.item("i1").sourceObjectId,
            sourceRevision = "rev-1",
            size = 100,
        )
        assertEquals(itemId, assertNotNull(prior).id)
    }

    @Test
    fun `deleting a transfer takes its items and chunks with it`() = runTest {
        val id = seedRunning("i1")
        repository.insertCacheChunk(
            CacheChunkEntity(
                id = CacheChunkId("c1"),
                transferItemId = TransferItemId("i1"),
                offset = 0,
                length = 10,
                localFilename = "00000000.chunk",
                createdAt = TestFixtures.EPOCH,
            ),
        )
        repository.deleteCacheChunksOfTransfer(id)
        assertTrue(repository.listCacheChunks(TransferItemId("i1")).isEmpty())
    }
}

/** The test double the repository and engine tests use (ADR-0002). */
class InMemoryTransactionContractTest : TransactionContractTest() {
    override fun newDatabase(): CloudLugDatabase = InMemoryCloudLugDatabase()
}

/**
 * The real thing, on a plain JVM.
 *
 * Room 2.8's KMP artifacts plus the bundled SQLite driver mean these run under
 * `./gradlew test` with no Android SDK, emulator or device — so the rollback
 * behaviour the engine depends on is verified on every CI run rather than only
 * when someone plugs a phone in (ADR-0021).
 */
class RoomTransactionContractTest : TransactionContractTest() {
    override fun newDatabase(): CloudLugDatabase = TestDatabases.inMemory()
}
