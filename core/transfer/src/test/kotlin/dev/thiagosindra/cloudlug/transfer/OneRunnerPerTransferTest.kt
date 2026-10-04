package dev.thiagosindra.cloudlug.transfer

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.provider.CloudDownload
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.storage.StorageSnapshot
import dev.thiagosindra.cloudlug.transfer.pipeline.NetworkMonitor
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import dev.thiagosindra.cloudlug.transfer.pipeline.StorageMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One transfer has at most one pipeline moving its bytes, even while a
 * cancelled one is still unwinding.
 *
 * The 2.5 GB phone run died with `OutOfMemoryError` on a thread whose
 * coroutine was `Cancelling`. A single pipeline's heap is flat
 * (`UploadHeapBoundTest`), so that thread was a run that had been told to stop
 * and had not — still holding its chunk buffer, its request body and its
 * download stream — while another run did the same.
 *
 * That is how cancellation meets blocking I/O. Cancelling a coroutine does not
 * interrupt a thread inside a socket read or an OkHttp call; the run carries
 * on until its next suspension point. The guard that keeps two runners off one
 * transfer asked `isActive`, which turns false the instant cancel is called,
 * so the next runner started at once beside the old one. On API 34+ that is
 * every app launch: `reconcile()` reschedules a running transfer's job, and
 * the platform answers by stopping the job it was running.
 *
 * The source here blocks a thread in `read`, exactly as a socket does, and
 * counts how many downloads are open at once.
 */
class OneRunnerPerTransferTest {

    private val harness = TransferTestHarness()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        harness.cleanUp()
    }

    @Test
    fun `a cancelled run still blocked in I-O finishes before the next one starts`() = runBlocking {
        val folder = harness.source.storage.folder("photos")
        harness.source.storage.file("big.bin", ByteArray(64 * 1024) { it.toByte() }, folder)
        val source = BlockingSource(harness.source)
        val engine = TransferEngine(
            repository = harness.repository,
            providers = ProviderRegistry { type -> if (type == source.type) source else harness.destination },
            chunkStore = harness.chunkStore,
            networkMonitor = NetworkMonitor { NetworkState.UNMETERED },
            storageMonitor = StorageMonitor { StorageSnapshot(freeBytes = 40L shl 30, totalBytes = 64L shl 30) },
            clock = harness.clock,
            zone = ZoneOffset.UTC,
        )
        val controller = TransferController(engine, harness.repository, scope)
        val transfer = harness.createTransfer()
        controller.prepare(transfer.id, harness.selectionOf(folder))

        // A platform job runs the transfer, and its thread blocks in the source.
        val first = scope.launch { controller.run(transfer.id) }
        assertTrue(source.blocked.await(10, TimeUnit.SECONDS), "the first run never reached the source")

        // The platform stops that job and starts another, as schedule() does to
        // a running job with the same id.
        first.cancel()
        val second = scope.launch { controller.run(transfer.id) }

        // Give the second run every chance to open its own download beside the
        // first, which is what it did before the guard waited.
        Thread.sleep(500)
        val overlapping = source.openNow.get()

        source.release.countDown()
        withTimeout(30_000) {
            first.join()
            second.join()
        }

        assertEquals(1, source.maxOpen.get(), "two pipelines were moving one transfer's bytes at once")
        assertEquals(1, overlapping)
    }

    /** Delegates to the fake, but its first download parks the reading thread until released. */
    private class BlockingSource(private val fake: CloudProvider) : CloudProvider by fake {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val openNow = AtomicInteger()
        val maxOpen = AtomicInteger()

        override suspend fun openDownload(account: AccountId, objectId: CloudObjectId, range: LongRange?): CloudDownload {
            val inner = fake.openDownload(account, objectId, range)
            maxOpen.accumulateAndGet(openNow.incrementAndGet(), ::maxOf)
            return object : CloudDownload by inner {
                override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    blocked.countDown()
                    // Not a suspension point, on purpose: a socket read is not one either.
                    release.await(30, TimeUnit.SECONDS)
                    return inner.read(destination, offset, length)
                }

                override fun close() {
                    openNow.decrementAndGet()
                    inner.close()
                }
            }
        }
    }
}
