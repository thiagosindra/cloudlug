package dev.thiagosindra.cloudlug.storage

import kotlin.test.Test
import kotlin.test.assertEquals

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

class CacheAccountantTest {

    private val policy = CacheBudgetPolicy()
    private val accountant = CacheAccountant(policy)

    @Test
    fun `the reserve is one gibibyte on small devices and five percent on large ones`() {
        // 16 GB phone: 5% is 0.8 GiB, so the 1 GiB floor wins (spec §15.1).
        assertEquals(GIB, policy.emergencyReserveBytes(StorageSnapshot(freeBytes = 8 * GIB, totalBytes = 16 * GIB)))
        // 512 GB device: 5% is 25.6 GiB, well above the floor.
        assertEquals(
            (512 * GIB * 0.05).toLong(),
            policy.emergencyReserveBytes(StorageSnapshot(freeBytes = 100 * GIB, totalBytes = 512 * GIB)),
        )
    }

    @Test
    fun `the budget halves what is left after the reserve and is capped by the configured maximum`() {
        // 40 GiB free on a 64 GiB device: reserve 3.2 GiB, usable 36.8 GiB,
        // half is 18.4 GiB, capped at the 5 GiB default maximum (spec §15).
        val roomy = StorageSnapshot(freeBytes = 40 * GIB, totalBytes = 64 * GIB)
        assertEquals(5 * GIB, accountant.budget(roomy))

        // 4 GiB free on a 16 GiB device: reserve 1 GiB, usable 3 GiB, half is 1.5 GiB.
        val tight = StorageSnapshot(freeBytes = 4 * GIB, totalBytes = 16 * GIB)
        assertEquals(3 * GIB / 2, accountant.budget(tight))
    }

    @Test
    fun `the reserve is never handed out even when the cache is empty`() {
        // Every free byte is inside the reserve.
        val starved = StorageSnapshot(freeBytes = GIB / 2, totalBytes = 16 * GIB)
        assertEquals(0, policy.usableStorageBytes(starved))
        assertEquals(0, accountant.budget(starved))
        assertEquals(CacheAllocation.WAIT_FOR_STORAGE, accountant.decide(starved, cachedBytes = 0))
    }

    @Test
    fun `a chunk is allowed while it fits both the budget and the device`() {
        val storage = StorageSnapshot(freeBytes = 40 * GIB, totalBytes = 64 * GIB)
        assertEquals(CacheAllocation.ALLOW, accountant.decide(storage, cachedBytes = 0))
        assertEquals(CacheAllocation.ALLOW, accountant.decide(storage, cachedBytes = 5 * GIB - 8 * MIB))
    }

    @Test
    fun `a full cache produces backpressure rather than a stall`() {
        // The uploader can still drain what is cached (spec §14, §15.1).
        val storage = StorageSnapshot(freeBytes = 40 * GIB, totalBytes = 64 * GIB)
        assertEquals(CacheAllocation.BACKPRESSURE, accountant.decide(storage, cachedBytes = 5 * GIB))
        assertEquals(CacheAllocation.BACKPRESSURE, accountant.decide(storage, cachedBytes = 5 * GIB - 1))
    }

    @Test
    fun `an empty cache with no room waits for storage`() {
        // 1 GiB + 4 MiB free on a 16 GiB device: usable is 4 MiB, less than one
        // 8 MiB chunk, and there is nothing cached left to drain.
        val storage = StorageSnapshot(freeBytes = GIB + 4 * MIB, totalBytes = 16 * GIB)
        assertEquals(CacheAllocation.WAIT_FOR_STORAGE, accountant.decide(storage, cachedBytes = 0))
    }

    @Test
    fun `a device that filled up while chunks were cached still drains first`() {
        val storage = StorageSnapshot(freeBytes = GIB, totalBytes = 16 * GIB)
        assertEquals(CacheAllocation.BACKPRESSURE, accountant.decide(storage, cachedBytes = 64 * MIB))
    }

    @Test
    fun `a smaller final chunk can be allowed where a full one cannot`() {
        val storage = StorageSnapshot(freeBytes = 40 * GIB, totalBytes = 64 * GIB)
        val nearlyFull = 5 * GIB - MIB
        assertEquals(CacheAllocation.BACKPRESSURE, accountant.decide(storage, nearlyFull, requestedBytes = 8 * MIB))
        assertEquals(CacheAllocation.ALLOW, accountant.decide(storage, nearlyFull, requestedBytes = MIB))
    }

    @Test
    fun `the default chunk size satisfies both providers in spec 15`() {
        // 8 MiB is a multiple of Drive's 256 KiB granularity and of Dropbox's
        // 4 MiB content_hash block.
        assertEquals(0, CacheBudgetPolicy.DEFAULT_NETWORK_CHUNK_BYTES % (256 * 1024))
        assertEquals(0, CacheBudgetPolicy.DEFAULT_NETWORK_CHUNK_BYTES % (4 * MIB))
    }
}
