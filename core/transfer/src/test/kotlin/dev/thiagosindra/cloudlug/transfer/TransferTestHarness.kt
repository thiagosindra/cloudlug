package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.inmemory.InMemoryCloudLugDatabase
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.storage.CacheBudgetPolicy
import dev.thiagosindra.cloudlug.storage.FileSystemChunkStore
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import dev.thiagosindra.cloudlug.transfer.policy.RetryPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val GIB = 1024L * 1024 * 1024

/**
 * Wires a complete engine against two [FakeCloudProvider]s and a real on-disk
 * chunk cache, so the end-to-end tests exercise the same code paths the app
 * will, minus the network.
 */
class TransferTestHarness(
    sourceCapabilities: ProviderCapabilities = FakeCloudProvider.defaultCapabilities(),
    destinationCapabilities: ProviderCapabilities = FakeCloudProvider.defaultCapabilities(
        nativeHashAlgorithm = HashAlgorithm.MD5,
    ),
    destinationQuotaBytes: Long? = 10 * GIB,
    cachePolicy: CacheBudgetPolicy = CacheBudgetPolicy(),
    private var networkSupplier: () -> NetworkState = { NetworkState.UNMETERED },
    private var storage: StorageSnapshot = StorageSnapshot(freeBytes = 40 * GIB, totalBytes = 64 * GIB),
) {
    val cacheRoot: Path = Files.createTempDirectory("cloudlug-engine-test")
    val chunkStore = FileSystemChunkStore(cacheRoot)
    val database = InMemoryCloudLugDatabase()
    val clock: Clock = Clock.fixed(Instant.parse("2026-09-17T09:57:00Z"), ZoneOffset.UTC)
    val repository = TransferRepository(database, clock)

    val source = FakeCloudProvider(
        type = ProviderType.DROPBOX,
        capabilities = sourceCapabilities,
        accountId = AccountId("source-account"),
    )

    val destination = FakeCloudProvider(
        type = ProviderType.GOOGLE_DRIVE,
        capabilities = destinationCapabilities,
        accountId = AccountId("destination-account"),
        totalQuotaBytes = destinationQuotaBytes,
    )

    val destinationRoot: CloudObjectId = destination.storage.folder("Destination")

    val engine = TransferEngine(
        repository = repository,
        providers = ProviderRegistry { type ->
            when (type) {
                source.type -> source
                destination.type -> destination
                else -> error("no provider for $type")
            }
        },
        chunkStore = chunkStore,
        networkMonitor = NetworkMonitor { networkSupplier() },
        storageMonitor = StorageMonitor { storage },
        cachePolicy = cachePolicy,
        retryPolicy = RetryPolicy(),
        clock = clock,
        zone = ZoneOffset.UTC,
    )

    fun onNetwork(state: NetworkState) {
        networkSupplier = { state }
    }

    /** Lets a test change connectivity partway through a run. */
    fun onNetwork(supplier: () -> NetworkState) {
        networkSupplier = supplier
    }

    fun withStorage(snapshot: StorageSnapshot) {
        storage = snapshot
    }

    suspend fun createTransfer(
        id: String = "t1",
        policy: TransferNetworkPolicy = TransferNetworkPolicy.UNMETERED_ONLY,
    ): TransferEntity = repository.createTransfer(
        TransferEntity(
            id = TransferId(id),
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
            sourceProvider = source.type,
            sourceAccountId = AccountId("source-account"),
            destinationProvider = destination.type,
            destinationAccountId = AccountId("destination-account"),
            destinationRootId = destinationRoot.opaqueId,
            destinationContainerName = "CloudLug - 2026-09-17 09-57",
            networkPolicy = policy,
        ),
    )

    fun selectionOf(vararg roots: CloudObjectId): CloudSelection = CloudSelection(
        accountId = AccountId("source-account"),
        roots = roots.map { source.storage.find(it.opaqueId) ?: error("no object ${it.opaqueId}") },
    )

    /** The destination tree as `path -> content`, for asserting on the result. */
    fun destinationTree(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fun walk(parentId: String, prefix: String) {
            destination.storage.childrenOf(parentId).forEach { child ->
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isFolder) {
                    result[path] = "<folder>"
                    walk(child.id.opaqueId, path)
                } else {
                    result[path] = destination.storage.contentOf(child.id.opaqueId)?.decodeToString().orEmpty()
                }
            }
        }
        walk(destinationRoot.opaqueId, "")
        return result
    }

    /** Files still on disk in the chunk cache. */
    suspend fun cachedChunkBytes(): Long = chunkStore.occupiedBytes()

    suspend fun statusesByName(transferId: TransferId): Map<String, TransferItemStatus> =
        repository.listItems(transferId).associate { it.sourceRelativePath.toString() to it.status }

    fun cleanUp() {
        Files.walk(cacheRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
