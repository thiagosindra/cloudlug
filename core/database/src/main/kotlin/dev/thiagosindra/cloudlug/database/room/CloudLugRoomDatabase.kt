package dev.thiagosindra.cloudlug.database.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.TypeConverters
import androidx.room.useWriterConnection
import dev.thiagosindra.cloudlug.database.dao.AccountDao
import dev.thiagosindra.cloudlug.database.dao.CacheChunkDao
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.dao.TransferDao
import dev.thiagosindra.cloudlug.database.dao.TransferItemDao
import dev.thiagosindra.cloudlug.database.entity.AccountEntity
import dev.thiagosindra.cloudlug.database.entity.CacheChunkEntity
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity

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
abstract class CloudLugRoomDatabase : RoomDatabase() {
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
 * How the platform opens the database (spec §4).
 *
 * This mirrors `NetworkMonitor` and `StorageMonitor`: `:core:database` owns the
 * schema, the DAOs and the §13 rules, and knows nothing about how a database is
 * opened on the platform it happens to run on. `:app` implements this with
 * Room's Android builder and the platform SQLite driver; the JVM tests
 * implement it with the bundled driver.
 *
 * It exists because the alternative shipped a crash. v0.2 called Room's
 * **JVM-only** `Room.databaseBuilder(name, factory)` from this module. It
 * compiled and passed every JVM test, then died on the first real device with
 * `NoSuchMethodError`: the Android artifact's `RoomDatabase.Builder` takes a
 * `Context`, so that constructor does not exist at runtime there. A JVM module
 * must not name a platform-specific construction API — see ADR-0025, and
 * `NoPlatformSpecificRoomApiTest`, which fails the build if one reappears here.
 */
fun interface CloudLugDatabaseFactory {
    fun open(): CloudLugDatabase
}

/**
 * Wraps a database the platform already built, so callers get the
 * [CloudLugDatabase] the repository depends on without this module ever
 * constructing one.
 */
fun CloudLugRoomDatabase.asCloudLugDatabase(): CloudLugDatabase = RoomCloudLugDatabase(this)
