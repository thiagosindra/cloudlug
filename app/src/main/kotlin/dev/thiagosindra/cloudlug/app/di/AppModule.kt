package dev.thiagosindra.cloudlug.app.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.thiagosindra.cloudlug.database.room.CloudLugDatabaseFactory
import dev.thiagosindra.cloudlug.database.room.CloudLugRoomDatabase
import dev.thiagosindra.cloudlug.database.room.asCloudLugDatabase
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.storage.ChunkStore
import dev.thiagosindra.cloudlug.storage.FileSystemChunkStore
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.transfer.TransferEngine
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/**
 * The Android half of the engine's ports.
 *
 * Every one of these was an interface before `:app` existed (ADR-0003), which is
 * what let the engine be written and tested with no Android SDK. This module is
 * where ConnectivityManager, StatFs, `filesDir` and Room finally arrive, and
 * nothing above them changed to accept them.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DATABASE_NAME = "cloudlug.db"

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Opens the §12 database, the one piece of persistence that has to know it
     * is on Android (ADR-0025).
     *
     * `:core:database` is a Kotlin/JVM module. Room publishes separate Android
     * and JVM artifacts whose `RoomDatabase.Builder` constructors differ — the
     * Android one takes a `Context` — so a JVM module naming either overload
     * compiles fine and then throws `NoSuchMethodError` on whichever platform
     * it did not pick. v0.2 shipped exactly that. Construction therefore sits
     * here, beside ConnectivityManager and StatFs, for the same reason those do.
     */
    @Provides
    @Singleton
    fun databaseFactory(@ApplicationContext context: Context) = CloudLugDatabaseFactory {
        Room.databaseBuilder(
            context = context,
            klass = CloudLugRoomDatabase::class.java,
            name = context.getDatabasePath(DATABASE_NAME).absolutePath,
        )
            // The platform's own SQLite, not the bundled build. v0.2 shipped
            // BundledSQLiteDriver to keep the app and the JVM tests on one
            // engine; that put a second native SQLite in the APK for no benefit
            // the app can observe, and the tests keep the bundled driver
            // regardless — ADR-0021 as revised.
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .asCloudLugDatabase()
    }

    @Provides
    @Singleton
    fun database(factory: CloudLugDatabaseFactory): CloudLugDatabase = factory.open()

    @Provides
    @Singleton
    fun repository(database: CloudLugDatabase, clock: Clock) = TransferRepository(database, clock)

    /**
     * Spec §15.2: `filesDir`, not `cacheDir`, so the OS cannot evict chunks
     * mid-transfer, and never shared storage.
     */
    @Provides
    @Singleton
    fun chunkStore(@ApplicationContext context: Context): ChunkStore =
        FileSystemChunkStore(File(context.filesDir, "cloudlug-cache").toPath())

    /**
     * Spec §16: the question is whether the network is *metered*, not whether it
     * is Wi-Fi. A metered hotspot is still cellular data as far as the user's
     * bill is concerned, and NET_CAPABILITY_NOT_METERED is what says so.
     */
    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context): NetworkMonitor = NetworkMonitor {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        NetworkState(
            connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        )
    }

    /** Spec §15, §15.1: free and total bytes on the volume holding `filesDir`. */
    @Provides
    @Singleton
    fun storageMonitor(@ApplicationContext context: Context): StorageMonitor = StorageMonitor {
        val stat = StatFs(context.filesDir.absolutePath)
        StorageSnapshot(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }

    @Provides
    @Singleton
    fun engine(
        repository: TransferRepository,
        providers: ProviderRegistry,
        chunkStore: ChunkStore,
        networkMonitor: NetworkMonitor,
        storageMonitor: StorageMonitor,
        clock: Clock,
    ) = TransferEngine(
        repository = repository,
        providers = providers,
        chunkStore = chunkStore,
        networkMonitor = networkMonitor,
        storageMonitor = storageMonitor,
        clock = clock,
    )

    /**
     * An application-scoped scope, so a transfer survives the screen that
     * started it. It does not survive process death — nothing does — which is
     * why the database is authoritative and `start` is safe to call again
     * (spec §2.4). Real background scheduling is §17 and v0.5: UIDT on API 34+
     * with a WorkManager fallback below it.
     */
    @Provides
    @Singleton
    fun transferScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun controller(
        engine: TransferEngine,
        repository: TransferRepository,
        scope: CoroutineScope,
    ) = TransferController(engine, repository, scope)

}
