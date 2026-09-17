package dev.thiagosindra.cloudlug.storage

import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Where cached chunks live (spec §15.2):
 *
 * ```
 * <root>/cloudlug-cache/<transfer-id>/<item-id>/00000000.chunk
 * ```
 *
 * The root is the app's `filesDir`, not `cacheDir`, so the OS cannot evict a
 * chunk mid-transfer, and it is excluded from backup. Chunk file names are the
 * zero-padded chunk index so an on-disk listing sorts in byte order, which
 * matters when recovering after process death.
 */
object ChunkCacheLayout {
    const val CACHE_DIRECTORY_NAME: String = "cloudlug-cache"

    fun transferDirectory(root: Path, transferId: TransferId): Path =
        root.resolve(CACHE_DIRECTORY_NAME).resolve(transferId.value)

    fun itemDirectory(root: Path, transferId: TransferId, itemId: TransferItemId): Path =
        transferDirectory(root, transferId).resolve(itemId.value)

    fun chunkFileName(index: Long): String {
        require(index >= 0) { "Chunk index must not be negative" }
        return "%08d.chunk".format(index)
    }
}

/**
 * Local storage for chunk bytes.
 *
 * Separated from the database so the two can be reconciled: the database is
 * authoritative about which chunks *should* exist (spec §2.4), and this reports
 * what is actually on disk. Cleanup after completion, cancellation or a crash
 * compares the two (spec §28).
 */
interface ChunkStore {
    /** Writes one chunk and returns its file name within the item's directory. */
    suspend fun write(
        transferId: TransferId,
        itemId: TransferItemId,
        index: Long,
        bytes: ByteArray,
        length: Int = bytes.size,
    ): String

    suspend fun read(transferId: TransferId, itemId: TransferItemId, fileName: String): ByteArray

    suspend fun delete(transferId: TransferId, itemId: TransferItemId, fileName: String)

    suspend fun deleteItem(transferId: TransferId, itemId: TransferItemId)

    suspend fun deleteTransfer(transferId: TransferId)

    /** Bytes currently occupied on disk by cached chunks. */
    suspend fun occupiedBytes(): Long

    /**
     * Deletes cache directories for transfers the database no longer knows
     * about. Run on app start and after cancellation (spec §28, §36).
     */
    suspend fun purgeOrphans(knownTransferIds: Set<TransferId>): Int
}

/** [ChunkStore] over a real directory. Works unchanged on the JVM and on Android. */
class FileSystemChunkStore(private val root: Path) : ChunkStore {

    override suspend fun write(
        transferId: TransferId,
        itemId: TransferItemId,
        index: Long,
        bytes: ByteArray,
        length: Int,
    ): String {
        require(length in 0..bytes.size) { "length $length is outside the buffer" }
        val directory = ChunkCacheLayout.itemDirectory(root, transferId, itemId)
        Files.createDirectories(directory)
        val fileName = ChunkCacheLayout.chunkFileName(index)
        // TRUNCATE_EXISTING rather than CREATE_NEW: a chunk re-downloaded after
        // an interrupted attempt must overwrite the partial file, not fail.
        Files.newOutputStream(
            directory.resolve(fileName),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { it.write(bytes, 0, length) }
        return fileName
    }

    override suspend fun read(transferId: TransferId, itemId: TransferItemId, fileName: String): ByteArray {
        val file = ChunkCacheLayout.itemDirectory(root, transferId, itemId).resolve(fileName)
        if (!file.exists()) throw IOException("Cached chunk $fileName is missing")
        return Files.readAllBytes(file)
    }

    override suspend fun delete(transferId: TransferId, itemId: TransferItemId, fileName: String) {
        ChunkCacheLayout.itemDirectory(root, transferId, itemId).resolve(fileName).deleteIfExists()
    }

    override suspend fun deleteItem(transferId: TransferId, itemId: TransferItemId) {
        deleteRecursively(ChunkCacheLayout.itemDirectory(root, transferId, itemId))
    }

    override suspend fun deleteTransfer(transferId: TransferId) {
        deleteRecursively(ChunkCacheLayout.transferDirectory(root, transferId))
    }

    override suspend fun occupiedBytes(): Long {
        val cacheRoot = root.resolve(ChunkCacheLayout.CACHE_DIRECTORY_NAME)
        if (!cacheRoot.exists()) return 0
        return Files.walk(cacheRoot).use { paths ->
            paths.filter { !it.isDirectory() }.mapToLong { it.fileSize() }.sum()
        }
    }

    override suspend fun purgeOrphans(knownTransferIds: Set<TransferId>): Int {
        val cacheRoot = root.resolve(ChunkCacheLayout.CACHE_DIRECTORY_NAME)
        if (!cacheRoot.exists()) return 0
        val known = knownTransferIds.mapTo(mutableSetOf()) { it.value }
        var purged = 0
        cacheRoot.listDirectoryEntries().forEach { entry ->
            if (entry.name !in known) {
                deleteRecursively(entry)
                purged++
            }
        }
        return purged
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }
}
