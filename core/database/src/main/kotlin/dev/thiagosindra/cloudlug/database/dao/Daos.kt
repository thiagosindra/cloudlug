package dev.thiagosindra.cloudlug.database.dao

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

/*
 * DAOs are deliberately dumb: they read and write rows and nothing else. Every
 * rule about *which* writes are legal lives in
 * `dev.thiagosindra.cloudlug.database.TransferRepository`, above this layer, so
 * that swapping the in-memory implementation for Room in v0.2 cannot change
 * behaviour (docs/decisions.md ADR-0002).
 *
 * Signatures are Room-shaped — suspend functions for writes, Flow for
 * observation — so the v0.2 implementations can be @Dao interfaces with
 * @Query/@Insert/@Update annotations and no other change.
 */

interface TransferDao {
    suspend fun insert(transfer: TransferEntity)

    suspend fun update(transfer: TransferEntity)

    suspend fun findById(id: TransferId): TransferEntity?

    suspend fun listAll(): List<TransferEntity>

    fun observeById(id: TransferId): Flow<TransferEntity?>

    /** Newest first, for the home screen (spec §24.1). */
    fun observeAll(): Flow<List<TransferEntity>>

    suspend fun delete(id: TransferId)
}

interface TransferItemDao {
    suspend fun insertAll(items: List<TransferItemEntity>)

    suspend fun update(item: TransferItemEntity)

    suspend fun findById(id: TransferItemId): TransferItemEntity?

    suspend fun listByTransfer(transferId: TransferId): List<TransferItemEntity>

    suspend fun listByStatus(transferId: TransferId, statuses: Set<TransferItemStatus>): List<TransferItemEntity>

    suspend fun countByStatus(transferId: TransferId): Map<TransferItemStatus, Int>

    /** Used to deduplicate a restarted enumeration (spec §11). */
    suspend fun findBySourceObjectId(transferId: TransferId, sourceObjectId: String): TransferItemEntity?

    /**
     * The idempotency record of spec §19.2: every completed item across all
     * transfers that moved this source object from this account.
     *
     * TODO(v0.2): in Room this is a join against the transfer table on
     * `sourceAccountId`; it is a query, not a second table.
     */
    suspend fun findCompletedForSourceObject(
        sourceAccountId: AccountId,
        sourceObjectId: String,
    ): List<TransferItemEntity>

    fun observeByTransfer(transferId: TransferId): Flow<List<TransferItemEntity>>

    suspend fun deleteByTransfer(transferId: TransferId)
}

interface CacheChunkDao {
    suspend fun insert(chunk: CacheChunkEntity)

    suspend fun update(chunk: CacheChunkEntity)

    suspend fun findById(id: CacheChunkId): CacheChunkEntity?

    /** Ordered by offset; the uploader consumes them in order. */
    suspend fun listByItem(transferItemId: TransferItemId): List<CacheChunkEntity>

    suspend fun listByStatus(transferItemId: TransferItemId, statuses: Set<CacheChunkStatus>): List<CacheChunkEntity>

    /** Total bytes currently held on local storage, for the cache budget (spec §15). */
    suspend fun totalCachedBytes(): Long

    suspend fun delete(id: CacheChunkId)

    suspend fun deleteByItem(transferItemId: TransferItemId)

    /** Every chunk of every item of a transfer, for cleanup (spec §22.3, §28). */
    suspend fun deleteByTransfer(transferId: TransferId)
}

interface AccountDao {
    suspend fun upsert(account: AccountEntity)

    suspend fun findById(id: AccountId): AccountEntity?

    suspend fun listAll(): List<AccountEntity>

    fun observeAll(): Flow<List<AccountEntity>>

    suspend fun delete(id: AccountId)
}

/**
 * The database as the rest of the app sees it (spec §2.4: the local database is
 * authoritative and in-memory workers are disposable).
 *
 * [withTransaction] must be atomic: if [block] throws, nothing it wrote is
 * visible afterwards. Every multi-row state change goes through it, because a
 * counter that disagrees with its items is exactly the kind of corruption that
 * survives a crash (spec §13).
 */
interface CloudLugDatabase {
    val transfers: TransferDao
    val items: TransferItemDao
    val chunks: CacheChunkDao
    val accounts: AccountDao

    suspend fun <T> withTransaction(block: suspend () -> T): T
}
