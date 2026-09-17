package dev.thiagosindra.cloudlug.database.inmemory

import dev.thiagosindra.cloudlug.database.dao.AccountDao
import dev.thiagosindra.cloudlug.database.dao.CacheChunkDao
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.dao.TransferDao
import dev.thiagosindra.cloudlug.database.dao.TransferItemDao
import dev.thiagosindra.cloudlug.database.entity.AccountEntity
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A [CloudLugDatabase] that keeps everything in memory.
 *
 * This is the v0.1 implementation used by unit tests and by the app demo until
 * Room lands in v0.2 (docs/decisions.md ADR-0002). It is not a toy substitute
 * for transactions: [withTransaction] snapshots every table on entry and
 * restores the snapshot if the block throws, so code that relies on rollback is
 * genuinely tested here and behaves the same once Room provides the real thing.
 *
 * Writes are serialised by a mutex, and transactions are not reentrant — a
 * nested [withTransaction] joins the outer one rather than deadlocking, which
 * matches Room's behaviour.
 */
class InMemoryCloudLugDatabase : CloudLugDatabase {

    private val lock = Mutex()
    private val transferRows = MutableStateFlow<Map<TransferId, TransferEntity>>(emptyMap())
    private val itemRows = MutableStateFlow<Map<TransferItemId, TransferItemEntity>>(emptyMap())
    private val chunkRows = MutableStateFlow<Map<CacheChunkId, CacheChunkEntity>>(emptyMap())
    private val accountRows = MutableStateFlow<Map<AccountId, AccountEntity>>(emptyMap())

    override val transfers: TransferDao = Transfers()
    override val items: TransferItemDao = Items()
    override val chunks: CacheChunkDao = Chunks()
    override val accounts: AccountDao = Accounts()

    override suspend fun <T> withTransaction(block: suspend () -> T): T {
        if (coroutineContext[TransactionMarker.Key] != null) return block()

        return lock.withLock {
            val snapshot = Snapshot(
                transfers = transferRows.value,
                items = itemRows.value,
                chunks = chunkRows.value,
                accounts = accountRows.value,
            )
            try {
                withContext(TransactionMarker()) { block() }
            } catch (throwable: Throwable) {
                transferRows.value = snapshot.transfers
                itemRows.value = snapshot.items
                chunkRows.value = snapshot.chunks
                accountRows.value = snapshot.accounts
                throw throwable
            }
        }
    }

    private class Snapshot(
        val transfers: Map<TransferId, TransferEntity>,
        val items: Map<TransferItemId, TransferItemEntity>,
        val chunks: Map<CacheChunkId, CacheChunkEntity>,
        val accounts: Map<AccountId, AccountEntity>,
    )

    /** Marks a coroutine as already inside a transaction. */
    private class TransactionMarker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TransactionMarker>
    }

    private inner class Transfers : TransferDao {
        override suspend fun insert(transfer: TransferEntity) {
            require(transfer.id !in transferRows.value) { "Transfer ${transfer.id} already exists" }
            transferRows.value += transfer.id to transfer
        }

        override suspend fun update(transfer: TransferEntity) {
            require(transfer.id in transferRows.value) { "No transfer ${transfer.id}" }
            transferRows.value += transfer.id to transfer
        }

        override suspend fun findById(id: TransferId): TransferEntity? = transferRows.value[id]

        override suspend fun listAll(): List<TransferEntity> = transferRows.value.values.sortedNewestFirst()

        override fun observeById(id: TransferId): Flow<TransferEntity?> = transferRows.map { it[id] }

        override fun observeAll(): Flow<List<TransferEntity>> =
            transferRows.map { it.values.sortedNewestFirst() }

        override suspend fun delete(id: TransferId) {
            transferRows.value -= id
            itemRows.value = itemRows.value.filterValues { it.transferId != id }
        }

        private fun Collection<TransferEntity>.sortedNewestFirst() =
            sortedByDescending { it.createdAt }
    }

    private inner class Items : TransferItemDao {
        override suspend fun insertAll(items: List<TransferItemEntity>) {
            items.forEach { require(it.id !in itemRows.value) { "Item ${it.id} already exists" } }
            itemRows.value += items.associateBy { it.id }
        }

        override suspend fun update(item: TransferItemEntity) {
            require(item.id in itemRows.value) { "No item ${item.id}" }
            itemRows.value += item.id to item
        }

        override suspend fun findById(id: TransferItemId): TransferItemEntity? = itemRows.value[id]

        override suspend fun listByTransfer(transferId: TransferId): List<TransferItemEntity> =
            itemRows.value.values.filter { it.transferId == transferId }.sortedBy { it.createdAt }

        override suspend fun listByStatus(
            transferId: TransferId,
            statuses: Set<TransferItemStatus>,
        ): List<TransferItemEntity> = listByTransfer(transferId).filter { it.status in statuses }

        override suspend fun countByStatus(transferId: TransferId): Map<TransferItemStatus, Int> =
            listByTransfer(transferId).groupingBy { it.status }.eachCount()

        override suspend fun findBySourceObjectId(
            transferId: TransferId,
            sourceObjectId: String,
        ): TransferItemEntity? = listByTransfer(transferId).firstOrNull { it.sourceObjectId == sourceObjectId }

        override suspend fun findCompletedForSourceObject(
            sourceAccountId: AccountId,
            sourceObjectId: String,
        ): List<TransferItemEntity> = itemRows.value.values.filter { item ->
            item.status == TransferItemStatus.COMPLETED &&
                item.sourceObjectId == sourceObjectId &&
                transferRows.value[item.transferId]?.sourceAccountId == sourceAccountId
        }

        override fun observeByTransfer(transferId: TransferId): Flow<List<TransferItemEntity>> =
            itemRows.map { rows ->
                rows.values.filter { it.transferId == transferId }.sortedBy { it.createdAt }
            }

        override suspend fun deleteByTransfer(transferId: TransferId) {
            itemRows.value = itemRows.value.filterValues { it.transferId != transferId }
        }
    }

    private inner class Chunks : CacheChunkDao {
        override suspend fun insert(chunk: CacheChunkEntity) {
            require(chunk.id !in chunkRows.value) { "Chunk ${chunk.id} already exists" }
            chunkRows.value += chunk.id to chunk
        }

        override suspend fun update(chunk: CacheChunkEntity) {
            require(chunk.id in chunkRows.value) { "No chunk ${chunk.id}" }
            chunkRows.value += chunk.id to chunk
        }

        override suspend fun findById(id: CacheChunkId): CacheChunkEntity? = chunkRows.value[id]

        override suspend fun listByItem(transferItemId: TransferItemId): List<CacheChunkEntity> =
            chunkRows.value.values.filter { it.transferItemId == transferItemId }.sortedBy { it.offset }

        override suspend fun listByStatus(
            transferItemId: TransferItemId,
            statuses: Set<CacheChunkStatus>,
        ): List<CacheChunkEntity> = listByItem(transferItemId).filter { it.status in statuses }

        override suspend fun totalCachedBytes(): Long = chunkRows.value.values
            .filter { it.status != CacheChunkStatus.DELETED }
            .sumOf { it.length }

        override suspend fun delete(id: CacheChunkId) {
            chunkRows.value -= id
        }

        override suspend fun deleteByItem(transferItemId: TransferItemId) {
            chunkRows.value = chunkRows.value.filterValues { it.transferItemId != transferItemId }
        }

        override suspend fun deleteByTransfer(transferId: TransferId) {
            val itemIds = itemRows.value.values.filter { it.transferId == transferId }.map { it.id }.toSet()
            chunkRows.value = chunkRows.value.filterValues { it.transferItemId !in itemIds }
        }
    }

    private inner class Accounts : AccountDao {
        override suspend fun upsert(account: AccountEntity) {
            accountRows.value += account.id to account
        }

        override suspend fun findById(id: AccountId): AccountEntity? = accountRows.value[id]

        override suspend fun listAll(): List<AccountEntity> = accountRows.value.values.toList()

        override fun observeAll(): Flow<List<AccountEntity>> = accountRows.map { it.values.toList() }

        override suspend fun delete(id: AccountId) {
            accountRows.value -= id
        }
    }
}
