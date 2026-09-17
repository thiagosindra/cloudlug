package dev.thiagosindra.cloudlug.storage

/** What local storage looks like right now. Supplied by the platform layer. */
data class StorageSnapshot(
    /** Free bytes on the volume holding the cache directory. */
    val freeBytes: Long,
    /** Total size of that volume, used for the proportional part of the reserve. */
    val totalBytes: Long,
) {
    init {
        require(freeBytes >= 0) { "freeBytes must not be negative" }
        require(totalBytes >= 0) { "totalBytes must not be negative" }
    }
}

/**
 * The cache sizing rules of spec §15 and §15.1.
 *
 * §15 writes `cacheBudget = min(usableStorage * 0.50, configuredMaximumCache)`
 * and §15.1 writes `usableStorage = freeSpace - max(1 GiB, totalDeviceStorage *
 * 0.05)`. Read in that order the reserve is subtracted first and the halving
 * applies to what is left, which is the conservative reading and the one
 * implemented here (docs/decisions.md ADR-0009).
 *
 * The chunk size is *not* a fraction of free space: it is a small,
 * provider-compatible network chunk inside a bounded cache (spec §15).
 */
data class CacheBudgetPolicy(
    val configuredMaximumCacheBytes: Long = DEFAULT_MAXIMUM_CACHE_BYTES,
    val networkChunkBytes: Long = DEFAULT_NETWORK_CHUNK_BYTES,
) {
    init {
        require(configuredMaximumCacheBytes > 0) { "configuredMaximumCache must be positive" }
        require(networkChunkBytes > 0) { "networkChunkSize must be positive" }
    }

    /** `max(1 GiB, totalDeviceStorage * 0.05)` — never handed out, whatever the pressure. */
    fun emergencyReserveBytes(storage: StorageSnapshot): Long =
        maxOf(MINIMUM_RESERVE_BYTES, (storage.totalBytes * RESERVE_FRACTION).toLong())

    /** Free space minus the emergency reserve, floored at zero. */
    fun usableStorageBytes(storage: StorageSnapshot): Long =
        (storage.freeBytes - emergencyReserveBytes(storage)).coerceAtLeast(0)

    /** How many bytes the cache may hold at once. */
    fun cacheBudgetBytes(storage: StorageSnapshot): Long =
        minOf((usableStorageBytes(storage) * USABLE_FRACTION).toLong(), configuredMaximumCacheBytes)

    companion object {
        const val DEFAULT_MAXIMUM_CACHE_BYTES: Long = 5L * 1024 * 1024 * 1024
        const val DEFAULT_NETWORK_CHUNK_BYTES: Long = 8L * 1024 * 1024
        const val MINIMUM_RESERVE_BYTES: Long = 1L * 1024 * 1024 * 1024
        const val RESERVE_FRACTION: Double = 0.05
        const val USABLE_FRACTION: Double = 0.50
    }
}

/**
 * What the download producer should do about one more chunk (spec §15.1).
 *
 * The distinction matters: [BACKPRESSURE] is normal operation — the cache is
 * full, the uploader will drain it — while [WAIT_FOR_STORAGE] means the device
 * itself is out of room and the transfer must hold in
 * `WAITING_FOR_STORAGE` until that changes.
 */
enum class CacheAllocation {
    ALLOW,
    BACKPRESSURE,
    WAIT_FOR_STORAGE,
}

/**
 * Decides whether another chunk may be written to the cache (spec §15, §15.1).
 *
 * Pure and synchronous: callers supply the current storage snapshot and how
 * many bytes the cache already holds, so this is trivially testable and holds
 * no state that could drift from the database.
 */
class CacheAccountant(private val policy: CacheBudgetPolicy = CacheBudgetPolicy()) {

    /**
     * @param cachedBytes bytes currently held by chunks that have not been deleted
     * @param requestedBytes size of the chunk about to be allocated
     */
    fun decide(
        storage: StorageSnapshot,
        cachedBytes: Long,
        requestedBytes: Long = policy.networkChunkBytes,
    ): CacheAllocation {
        require(cachedBytes >= 0) { "cachedBytes must not be negative" }
        require(requestedBytes > 0) { "requestedBytes must be positive" }

        val fitsOnDevice = requestedBytes <= policy.usableStorageBytes(storage)
        val fitsInBudget = cachedBytes + requestedBytes <= policy.cacheBudgetBytes(storage)

        return when {
            fitsOnDevice && fitsInBudget -> CacheAllocation.ALLOW
            // The uploader can still drain what is cached, so this is backpressure,
            // not a stall — spec §15.1: "stop downloading and let the uploader
            // drain the cache".
            cachedBytes > 0 -> CacheAllocation.BACKPRESSURE
            // Cache is empty and a single chunk still does not fit.
            else -> CacheAllocation.WAIT_FOR_STORAGE
        }
    }

    fun budget(storage: StorageSnapshot): Long = policy.cacheBudgetBytes(storage)

    fun reserve(storage: StorageSnapshot): Long = policy.emergencyReserveBytes(storage)
}
