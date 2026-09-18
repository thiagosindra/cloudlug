package dev.thiagosindra.cloudlug.database.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.thiagosindra.cloudlug.database.dao.AccountDao
import dev.thiagosindra.cloudlug.database.dao.CacheChunkDao
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

/*
 * Room implementations of the DAO interfaces in `dao/`.
 *
 * Each one implements the interface the repository already depended on, so the
 * in-memory store and Room are interchangeable and every §13 rule stays in
 * TransferRepository above them (docs/decisions.md ADR-0002). These types add no
 * behaviour: they are queries.
 */

@Dao
internal interface RoomTransferDao : TransferDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insert(transfer: TransferEntity)

    @Update
    override suspend fun update(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id")
    override suspend fun findById(id: TransferId): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    override suspend fun listAll(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE id = :id")
    override fun observeById(id: TransferId): Flow<TransferEntity?>

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    override fun observeAll(): Flow<List<TransferEntity>>

    @Query("DELETE FROM transfers WHERE id = :id")
    override suspend fun delete(id: TransferId)
}

@Dao
internal interface RoomTransferItemDao : TransferItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insertAll(items: List<TransferItemEntity>)

    @Update
    override suspend fun update(item: TransferItemEntity)

    @Query("SELECT * FROM transfer_items WHERE id = :id")
    override suspend fun findById(id: TransferItemId): TransferItemEntity?

    // Folders before the files inside them, which is the order the engine needs
    // so a destination parent exists before its children are uploaded (§10).
    @Query("SELECT * FROM transfer_items WHERE transferId = :transferId ORDER BY createdAt, id")
    override suspend fun listByTransfer(transferId: TransferId): List<TransferItemEntity>

    @Query(
        "SELECT * FROM transfer_items WHERE transferId = :transferId AND status IN (:statuses) " +
            "ORDER BY createdAt, id",
    )
    override suspend fun listByStatus(
        transferId: TransferId,
        statuses: Set<TransferItemStatus>,
    ): List<TransferItemEntity>

    @Query("SELECT status, COUNT(*) AS count FROM transfer_items WHERE transferId = :transferId GROUP BY status")
    override suspend fun countByStatus(
        transferId: TransferId,
    ): Map<@MapColumn("status") TransferItemStatus, @MapColumn("count") Int>

    @Query("SELECT * FROM transfer_items WHERE transferId = :transferId AND sourceObjectId = :sourceObjectId")
    override suspend fun findBySourceObjectId(
        transferId: TransferId,
        sourceObjectId: String,
    ): TransferItemEntity?

    /**
     * Spec §19.2: the idempotency record — every completed item that moved this
     * source object from this account, in any transfer.
     *
     * The join is what ADR-0002 predicted: a query against the transfer table on
     * `sourceAccountId`, not a second table. Newest first, so a caller taking
     * the first row gets the most recent transfer of those bytes.
     */
    @Query(
        "SELECT i.* FROM transfer_items i JOIN transfers t ON i.transferId = t.id " +
            "WHERE t.sourceAccountId = :sourceAccountId AND i.sourceObjectId = :sourceObjectId " +
            "AND i.status = 'COMPLETED' ORDER BY i.updatedAt DESC",
    )
    override suspend fun findCompletedForSourceObject(
        sourceAccountId: AccountId,
        sourceObjectId: String,
    ): List<TransferItemEntity>

    @Query("SELECT * FROM transfer_items WHERE transferId = :transferId ORDER BY createdAt, id")
    override fun observeByTransfer(transferId: TransferId): Flow<List<TransferItemEntity>>

    @Query("DELETE FROM transfer_items WHERE transferId = :transferId")
    override suspend fun deleteByTransfer(transferId: TransferId)
}

@Dao
internal interface RoomCacheChunkDao : CacheChunkDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insert(chunk: CacheChunkEntity)

    @Update
    override suspend fun update(chunk: CacheChunkEntity)

    @Query("SELECT * FROM cache_chunks WHERE id = :id")
    override suspend fun findById(id: CacheChunkId): CacheChunkEntity?

    @Query("SELECT * FROM cache_chunks WHERE transferItemId = :transferItemId ORDER BY offset")
    override suspend fun listByItem(transferItemId: TransferItemId): List<CacheChunkEntity>

    @Query(
        "SELECT * FROM cache_chunks WHERE transferItemId = :transferItemId AND status IN (:statuses) " +
            "ORDER BY offset",
    )
    override suspend fun listByStatus(
        transferItemId: TransferItemId,
        statuses: Set<CacheChunkStatus>,
    ): List<CacheChunkEntity>

    /**
     * Bytes currently occupying the cache budget (spec §15).
     *
     * DELETED rows are excluded because their bytes are already gone; counting
     * them would shrink the budget until the row was reaped and could park a
     * transfer in WAITING_FOR_STORAGE with an empty cache directory.
     */
    @Query("SELECT COALESCE(SUM(length), 0) FROM cache_chunks WHERE status != 'DELETED'")
    override suspend fun totalCachedBytes(): Long

    @Query("DELETE FROM cache_chunks WHERE id = :id")
    override suspend fun delete(id: CacheChunkId)

    @Query("DELETE FROM cache_chunks WHERE transferItemId = :transferItemId")
    override suspend fun deleteByItem(transferItemId: TransferItemId)

    @Query(
        "DELETE FROM cache_chunks WHERE transferItemId IN " +
            "(SELECT id FROM transfer_items WHERE transferId = :transferId)",
    )
    override suspend fun deleteByTransfer(transferId: TransferId)
}

@Dao
internal interface RoomAccountDao : AccountDao {

    @Upsert
    override suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    override suspend fun findById(id: AccountId): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY createdAt")
    override suspend fun listAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY createdAt")
    override fun observeAll(): Flow<List<AccountEntity>>

    @Query("DELETE FROM accounts WHERE id = :id")
    override suspend fun delete(id: AccountId)
}

