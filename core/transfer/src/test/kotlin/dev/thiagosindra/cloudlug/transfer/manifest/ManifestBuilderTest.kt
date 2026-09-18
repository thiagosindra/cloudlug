package dev.thiagosindra.cloudlug.transfer.manifest

import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.transfer.TransferTestHarness
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestBuilderTest {

    private val harness = TransferTestHarness()

    @AfterTest
    fun cleanUp() = harness.cleanUp()

    private suspend fun buildManifest(
        vararg roots: dev.thiagosindra.cloudlug.provider.CloudObjectId,
        destinationCapabilities: dev.thiagosindra.cloudlug.provider.ProviderCapabilities =
            harness.destination.capabilities,
        selection: dev.thiagosindra.cloudlug.provider.CloudSelection? = null,
        pageSize: Int = 200,
    ): ManifestSummary {
        val transfer = harness.createTransfer()
        harness.repository.transitionTransfer(
            transfer.id,
            dev.thiagosindra.cloudlug.model.TransferStatus.PREPARING,
        )
        return ManifestBuilder(harness.repository, harness.clock, pageSize).build(
            transfer = transfer,
            source = harness.source,
            destinationCapabilities = destinationCapabilities,
            selection = selection ?: harness.selectionOf(*roots),
        )
    }

    @Test
    fun `the source tree becomes relative destination paths`() = runTest {
        val photos = harness.source.storage.folder("photos")
        val year = harness.source.storage.folder("2026", photos)
        harness.source.storage.file("1.png", ByteArray(10), year)

        buildManifest(photos)

        assertEquals(
            listOf("photos", "photos/2026", "photos/2026/1.png"),
            harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1"))
                .map { it.sourceRelativePath.toString() },
        )
    }

    @Test
    fun `a selection deeper in the tree keeps its ancestors`() = runTest {
        // Spec §10: selecting /photos/2026/April lands at photos/2026/April.
        val april = harness.source.storage.folder("April")
        harness.source.storage.file("1.png", ByteArray(10), april)

        buildManifest(selection = harness.selectionAt(april to "photos/2026/April"))

        assertEquals(
            listOf("photos/2026/April", "photos/2026/April/1.png"),
            harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1"))
                .map { it.sourceRelativePath.toString() },
        )
    }

    @Test
    fun `empty folders are part of the manifest`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.folder("empty", root)

        val summary = buildManifest(root)

        assertEquals(2, summary.folders)
        assertEquals(0, summary.files)
    }

    @Test
    fun `sizes are totalled for the review step`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.file("a.bin", ByteArray(300), root)
        harness.source.storage.file("b.bin", ByteArray(700), root)

        val summary = buildManifest(root)

        assertEquals(2, summary.files)
        assertEquals(1_000, summary.bytes)
        assertEquals(2, summary.transferableFiles)
    }

    @Test
    fun `native documents and shortcuts are classified before any byte moves`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.nativeDocument("Plan", root)
        harness.source.storage.shortcut("Link", root)

        val summary = buildManifest(root)

        assertEquals(2, summary.unsupported)
        val items = harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1"))
            .associateBy { it.filename }
        assertEquals(
            ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
            items.getValue("Plan").statusReason,
        )
        assertEquals(ItemStatusReason.UNSUPPORTED_SHORTCUT, items.getValue("Link").statusReason)
    }

    @Test
    fun `siblings that collide case-insensitively are a conflict at manifest time`() = runTest {
        // Spec §20.3: a Drive source can hold Report.txt and report.txt in one
        // folder; a case-folding destination cannot.
        val root = harness.source.storage.folder("tree")
        harness.source.storage.file("Report.txt", ByteArray(5), root)
        harness.source.storage.file("report.txt", ByteArray(5), root)

        val summary = buildManifest(
            root,
            destinationCapabilities = FakeCloudProvider.defaultCapabilities(caseSensitiveNames = false),
        )

        assertEquals(2, summary.conflicts, "both siblings conflict; neither is entitled to the name")
        harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1"))
            .filter { it.filename.endsWith(".txt") }
            .forEach {
                assertEquals(TransferItemStatus.CONFLICT, it.status)
                assertEquals(ItemStatusReason.CONFLICT_CASE_INSENSITIVE_COLLISION, it.statusReason)
            }
    }

    @Test
    fun `identical sibling names collide even on a case-sensitive destination that forbids duplicates`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.file("twin.txt", ByteArray(5), root)
        harness.source.storage.file("twin.txt", ByteArray(5), root)

        val summary = buildManifest(root)

        assertEquals(2, summary.conflicts)
    }

    @Test
    fun `a name the destination cannot represent is a conflict, not an auto-rename`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.file("a:b.txt", ByteArray(5), root)
        harness.source.storage.file("x".repeat(300), ByteArray(5), root)

        val summary = buildManifest(
            root,
            destinationCapabilities = FakeCloudProvider.defaultCapabilities(
                illegalNameCharacters = setOf(':', '/', '\\'),
                maxNameLength = 255,
            ),
        )

        assertEquals(2, summary.conflicts)
        harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1"))
            .filter { it.status == TransferItemStatus.CONFLICT }
            .forEach { assertEquals(ItemStatusReason.CONFLICT_ILLEGAL_NAME, it.statusReason) }
    }

    @Test
    fun `enumeration writes pages with a cursor so it can resume`() = runTest {
        val root = harness.source.storage.folder("tree")
        repeat(7) { harness.source.storage.file("file-$it.bin", ByteArray(4), root) }

        buildManifest(root, pageSize = 3)

        val transfer = harness.repository.findTransfer(dev.thiagosindra.cloudlug.model.TransferId("t1"))
        assertEquals(8, harness.repository.listItems(dev.thiagosindra.cloudlug.model.TransferId("t1")).size)
        assertTrue(transfer?.enumerationCursor == null, "the cursor is cleared once enumeration completes")
    }

    @Test
    fun `a restarted enumeration does not duplicate the manifest`() = runTest {
        val root = harness.source.storage.folder("tree")
        harness.source.storage.file("a.bin", ByteArray(4), root)

        val transfer = harness.createTransfer()
        harness.repository.transitionTransfer(
            transfer.id,
            dev.thiagosindra.cloudlug.model.TransferStatus.PREPARING,
        )
        val builder = ManifestBuilder(harness.repository, harness.clock)
        val selection = harness.selectionOf(root)
        repeat(2) {
            builder.build(transfer, harness.source, harness.destination.capabilities, selection)
        }

        assertEquals(2, harness.repository.listItems(transfer.id).size, "one folder and one file, written once")
        assertEquals(1, harness.repository.findTransfer(transfer.id)?.totalFiles)
    }
}
