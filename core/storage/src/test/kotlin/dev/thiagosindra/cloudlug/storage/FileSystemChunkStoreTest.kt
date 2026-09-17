package dev.thiagosindra.cloudlug.storage

import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemChunkStoreTest {

    private val root: Path = Files.createTempDirectory("cloudlug-test")
    private val store = FileSystemChunkStore(root)
    private val transfer = TransferId("t1")
    private val item = TransferItemId("i1")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `chunks land in the layout spec 15-2 describes`() = runTest {
        val name = store.write(transfer, item, index = 0, bytes = byteArrayOf(1, 2, 3))

        assertEquals("00000000.chunk", name)
        assertTrue(root.resolve("cloudlug-cache/t1/i1/00000000.chunk").exists())
    }

    @Test
    fun `chunk names sort in byte order`() = runTest {
        val names = listOf(0L, 1L, 10L, 1_000L).map { store.write(transfer, item, it, byteArrayOf(0)) }
        assertEquals(names, names.sorted())
    }

    @Test
    fun `a rewritten chunk replaces the partial one`() = runTest {
        store.write(transfer, item, 0, ByteArray(64) { 9 })
        store.write(transfer, item, 0, byteArrayOf(1, 2))

        assertContentEquals(byteArrayOf(1, 2), store.read(transfer, item, "00000000.chunk"))
    }

    @Test
    fun `only the requested length is written`() = runTest {
        val name = store.write(transfer, item, 0, ByteArray(8) { it.toByte() }, length = 3)
        assertContentEquals(byteArrayOf(0, 1, 2), store.read(transfer, item, name))
    }

    @Test
    fun `reading a missing chunk fails loudly rather than returning nothing`() = runTest {
        assertFailsWith<java.io.IOException> { store.read(transfer, item, "00000000.chunk") }
    }

    @Test
    fun `occupied bytes reflect what is on disk`() = runTest {
        store.write(transfer, item, 0, ByteArray(100))
        store.write(transfer, item, 1, ByteArray(50))
        assertEquals(150, store.occupiedBytes())

        store.delete(transfer, item, "00000001.chunk")
        assertEquals(100, store.occupiedBytes())
    }

    @Test
    fun `deleting an item removes its directory and leaves siblings alone`() = runTest {
        store.write(transfer, item, 0, ByteArray(10))
        store.write(transfer, TransferItemId("i2"), 0, ByteArray(20))

        store.deleteItem(transfer, item)

        assertFalse(root.resolve("cloudlug-cache/t1/i1").exists())
        assertEquals(20, store.occupiedBytes())
    }

    @Test
    fun `deleting a transfer removes every chunk it owns`() = runTest {
        store.write(transfer, item, 0, ByteArray(10))
        store.write(TransferId("t2"), item, 0, ByteArray(20))

        store.deleteTransfer(transfer)

        assertFalse(root.resolve("cloudlug-cache/t1").exists())
        assertEquals(20, store.occupiedBytes())
    }

    @Test
    fun `orphaned directories from a previous process are purged on start`() = runTest {
        store.write(transfer, item, 0, ByteArray(10))
        store.write(TransferId("t-gone"), item, 0, ByteArray(10))

        assertEquals(1, store.purgeOrphans(knownTransferIds = setOf(transfer)))
        assertEquals(10, store.occupiedBytes())
        assertFalse(root.resolve("cloudlug-cache/t-gone").exists())
    }

    @Test
    fun `purging an empty cache is harmless`() = runTest {
        assertEquals(0, store.purgeOrphans(knownTransferIds = emptySet()))
        assertEquals(0, store.occupiedBytes())
    }
}
