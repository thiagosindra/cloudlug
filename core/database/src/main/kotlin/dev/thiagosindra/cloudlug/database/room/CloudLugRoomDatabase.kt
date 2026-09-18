package dev.thiagosindra.cloudlug.database.room

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.TypeConverters
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.thiagosindra.cloudlug.database.dao.AccountDao
import dev.thiagosindra.cloudlug.database.dao.CacheChunkDao
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.dao.TransferDao
import dev.thiagosindra.cloudlug.database.dao.TransferItemDao
import dev.thiagosindra.cloudlug.database.entity.AccountEntity
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import kotlinx.coroutines.Dispatchers

/**
 * The §12 persistence model as Room sees it.
 *
 * `exportSchema = true` and the JSON lives in `core/database/schemas`, committed
 * alongside the code: a schema change then shows up in review as a diff rather
 * than as a migration someone discovers on a user's device. Version 1 is the
 * first shipped schema, so there are no migrations yet.
 */
@Database(
    entities = [
        TransferEntity::class,
        TransferItemEntity::class,
        CacheChunkEntity::class,
        AccountEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class CloudLugRoomDatabase : RoomDatabase() {
    abstract fun transferDao(): RoomTransferDao
    abstract fun transferItemDao(): RoomTransferItemDao
    abstract fun cacheChunkDao(): RoomCacheChunkDao
    abstract fun accountDao(): RoomAccountDao
}

/**
 * Adapts Room to the [CloudLugDatabase] interface the repository depends on.
 *
 * The only thing this adds over the generated DAOs is [withTransaction], and it
 * is the reason Room can replace the in-memory store without a rule changing:
 * every §13 transition in `TransferRepository` assumes a block that throws
 * leaves nothing behind. `useWriterConnection` pins the block to the single
 * writer connection and `IMMEDIATE` takes the write lock up front, so a
 * rejected transition rolls back the counter update that accompanied it rather
 * than half-applying it (spec §2.4, §13).
 */
internal class RoomCloudLugDatabase(
    private val database: CloudLugRoomDatabase,
) : CloudLugDatabase, AutoCloseable {

    override val transfers: TransferDao = database.transferDao()
    override val items: TransferItemDao = database.transferItemDao()
    override val chunks: CacheChunkDao = database.cacheChunkDao()
    override val accounts: AccountDao = database.accountDao()

    override suspend fun <T> withTransaction(block: suspend () -> T): T =
        database.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }

    override fun close() = database.close()
}

/**
 * Builds the Room-backed [CloudLugDatabase].
 *
 * Both factories use [BundledSQLiteDriver], so the JVM tests and the Android
 * app run the same SQLite build rather than whatever version happens to ship
 * with a given device — see docs/decisions.md ADR-0021.
 */
object CloudLugDatabases {

    /** On-disk database. On Android, pass `context.getDatabasePath(...)`. */
    fun atPath(path: String): CloudLugDatabase =
        RoomCloudLugDatabase(
            Room.databaseBuilder<CloudLugRoomDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build(),
        )

    /**
     * In-memory database, discarded when closed. This is what the Room tests
     * use, and it needs no Android device — spec §33 v0.2 asks for the Room
     * transaction tests to run on the JVM if the Room version allows it, and
     * 2.8's KMP artifacts do.
     */
    fun inMemory(): CloudLugDatabase =
        RoomCloudLugDatabase(
            Room.inMemoryDatabaseBuilder<CloudLugRoomDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build(),
        )
}
