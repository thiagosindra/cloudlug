package dev.thiagosindra.cloudlug.database.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import kotlinx.coroutines.Dispatchers

/**
 * The JVM half of [CloudLugDatabaseFactory], for tests only.
 *
 * This deliberately lives in the test source set. `Room.inMemoryDatabaseBuilder`
 * has a JVM overload taking a factory and an Android overload taking a
 * `Context`; naming either from `main` puts a call site in the APK that does not
 * resolve at runtime on the other platform, which is exactly what crashed v0.2
 * (ADR-0025). Tests only ever run on the JVM, so here it is safe and correct.
 */
object TestDatabases {

    fun inMemory(): CloudLugDatabase =
        Room.inMemoryDatabaseBuilder<CloudLugRoomDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .asCloudLugDatabase()
}
