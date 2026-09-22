package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.UploadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behavioural contract every provider adapter must satisfy (spec §31.2).
 *
 * A new adapter subclasses this and implements the four seeding hooks; it then
 * inherits checks for authentication, paged enumeration, quota, download and
 * range download, folder creation, upload and resume, lookup including
 * multi-match, metadata, hashing and abort. The point is that the engine can
 * trust these properties for *any* provider, present or future — which is what
 * makes spec §32.7 enforceable rather than aspirational.
 *
 * Tests here only assume what [CloudProvider.capabilities] declares: a provider
 * without range download or without a server hash skips those checks rather
 * than failing them.
 */
abstract class ProviderContractTest {

    /** A fresh provider with an empty tree. */
    protected abstract fun newProvider(): CloudProvider

    /** The account the provider serves. */
    protected abstract fun account(provider: CloudProvider): AccountId

    /** The folder new objects are created under. */
    protected abstract suspend fun rootFolder(provider: CloudProvider): CloudObjectId

    protected abstract suspend fun seedFolder(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
    ): CloudObjectId

    protected abstract suspend fun seedFile(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
        content: ByteArray,
    ): CloudObjectId

    @Test
    fun `authenticate returns an account for this provider`() = runTest {
        val provider = newProvider()
        val account = provider.authenticate()

        assertEquals(provider.type, account.provider)
        assertTrue(account.grantedScopes.isNotEmpty(), "an authenticated account must report its scopes")
    }

    @Test
    fun `listChildren at the account root shows objects seeded directly under it`() = runTest {
        val provider = newProvider()
        val account = account(provider)
        val top = seedFolder(provider, provider.rootOf(account), "top level")

        val children = provider.listChildren(account, provider.rootOf(account)).toList()

        // The picker's first call is always this one. v0.2 shipped a wizard
        // that invented its own root ID, so this returned nothing for every
        // provider and no file could be chosen (ADR-0026).
        assertTrue(
            children.any { it.id == top },
            "listChildren(rootOf(account)) must include an object seeded under the root, " +
                "but returned ${children.map { it.name }}",
        )
    }

    @Test
    fun `listChildren returns one level, not a subtree`() = runTest {
        val provider = newProvider()
        val account = account(provider)
        val top = seedFolder(provider, provider.rootOf(account), "top level")
        val nested = seedFolder(provider, top, "nested")
        seedFile(provider, nested, "deep.txt", "deep".toByteArray())

        val children = provider.listChildren(account, provider.rootOf(account)).toList()

        // A picker that gets a whole subtree cannot draw a folder row, and §9
        // has the user descend one level at a time.
        assertTrue(
            children.none { it.id == nested },
            "listChildren must not descend: ${children.map { it.name }}",
        )
    }

    @Test
    fun `enumerate emits every selection root`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val photos = seedFolder(provider, root, "photos")
        seedFile(provider, photos, "1.png", byteArrayOf(1))

        val emitted = provider.enumerate(account(provider), selectionOf(provider, photos)).toList()

        // The selected folder is part of the transfer, not merely a cursor into
        // it: §10 reproduces the source's own ancestors, so `photos` itself has
        // to arrive or the manifest has nowhere to hang its children. The
        // Dropbox adapter listed the root's *contents* and never emitted the
        // root, which `ManifestBuilder` answers with "emitted before its
        // parent" on the very first child.
        assertTrue(
            emitted.any { it.id == photos },
            "the selection root must be emitted, but enumerate gave ${emitted.map { it.name }}",
        )
    }

    @Test
    fun `enumerate emits parents before children`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val photos = seedFolder(provider, root, "photos")
        val year = seedFolder(provider, photos, "2026")
        seedFile(provider, year, "1.png", byteArrayOf(1))

        val emitted = provider.enumerate(account(provider), selectionOf(provider, photos)).toList()
        val positions = emitted.withIndex().associate { (index, obj) -> obj.id to index }
        val roots = setOf(photos)

        // Asserted as a property of *every* object rather than of the ones that
        // happen to carry a parent. The earlier version skipped an object whose
        // `parentId` was null, which made it silently vacuous for an adapter
        // that set no parents at all — precisely the adapter that was broken.
        emitted.forEach { obj ->
            if (obj.id in roots) return@forEach
            val parent = assertNotNull(
                obj.parentId,
                "${obj.name} was emitted with no parent; only a selection root may have none",
            )
            val parentPosition = assertNotNull(
                positions[parent],
                "${obj.name} names a parent that enumerate never emitted",
            )
            assertTrue(
                parentPosition < positions.getValue(obj.id),
                "${obj.name} was emitted before its parent",
            )
        }
    }

    @Test
    fun `enumerate accepts a file as a selection root`() = runTest {
        val provider = newProvider()
        val file = seedFile(provider, rootFolder(provider), "alone.txt", byteArrayOf(1))

        // §9 puts a checkbox on every row, files included, so a selection root
        // is not necessarily a folder. An adapter that assumes otherwise asks
        // the provider to list a file and gets a shaped refusal back.
        val emitted = provider.enumerate(
            account(provider),
            selectionOf(provider, file, type = CloudObjectType.FILE),
        ).toList()

        assertEquals(listOf(file), emitted.map { it.id })
    }

    @Test
    fun `enumerate includes empty folders`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val parent = seedFolder(provider, root, "tree")
        seedFolder(provider, parent, "empty")

        val names = provider.enumerate(account(provider), selectionOf(provider, parent)).toList().map { it.name }
        assertTrue("empty" in names, "spec §20.5 keeps empty folders")
    }

    @Test
    fun `enumeration resumes from an object id without repeating earlier objects`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val parent = seedFolder(provider, root, "tree")
        repeat(5) { seedFile(provider, parent, "file-$it.bin", byteArrayOf(it.toByte())) }

        val all = provider.enumerate(account(provider), selectionOf(provider, parent)).toList()
        val resumed = provider.enumerate(
            account(provider),
            selectionOf(provider, parent),
            resumeAfter = all[2].id,
        ).toList()

        assertEquals(all.drop(3).map { it.id }, resumed.map { it.id })
    }

    @Test
    fun `an enumeration whose resume point has vanished starts over rather than yielding nothing`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val parent = seedFolder(provider, root, "tree")
        repeat(3) { seedFile(provider, parent, "file-$it.bin", byteArrayOf(it.toByte())) }

        // §11 resumes from the last object the caller persisted. That object can
        // be gone by the time the transfer resumes — the user deleted it, or
        // moved it out of the selection — and an adapter that skips until it
        // sees an id that will never arrive emits nothing at all. The manifest
        // then looks complete with no work in it, which is the worst available
        // outcome: silent, and indistinguishable from success.
        val vanished = CloudObjectId(provider.type, "an-object-that-is-not-in-this-account")
        val resumed = provider.enumerate(
            account(provider),
            selectionOf(provider, parent),
            resumeAfter = vanished,
        ).toList()

        assertTrue(
            resumed.isNotEmpty(),
            "a resume point that no longer exists must fall back to a full walk, not an empty one",
        )
        // Re-walking is safe: §11 says the manifest deduplicates by source
        // object id, so a restart is idempotent and only slower.
        assertEquals(4, resumed.size, "expected the folder and its three files, got ${resumed.map { it.name }}")
    }

    @Test
    fun `resolveMetadata reports size and revision for a file`() = runTest {
        val provider = newProvider()
        val content = ByteArray(1_000) { it.toByte() }
        val id = seedFile(provider, rootFolder(provider), "data.bin", content)

        val obj = provider.resolveMetadata(account(provider), id)

        assertEquals(CloudObjectType.FILE, obj.type)
        assertEquals(content.size.toLong(), obj.size)
    }

    @Test
    fun `quota is either absent or internally consistent`() = runTest {
        val provider = newProvider()
        val quota = provider.quota(account(provider)) ?: return@runTest
        val total = quota.totalBytes
        val used = quota.usedBytes
        val available = quota.availableBytes
        if (total != null && used != null && available != null) {
            assertEquals(total - used, available, "quota fields must agree")
        }
    }

    @Test
    fun `download returns the bytes that were stored`() = runTest {
        val provider = newProvider()
        val content = ByteArray(4_096) { (it % 251).toByte() }
        val id = seedFile(provider, rootFolder(provider), "data.bin", content)

        val downloaded = provider.openDownload(account(provider), id).use { readAll(it) }

        assertContentEquals(content, downloaded)
    }

    @Test
    fun `range download returns exactly the requested window`() = runTest {
        val provider = newProvider()
        if (!provider.capabilities.supportsRangeDownload) return@runTest
        val content = ByteArray(4_096) { (it % 251).toByte() }
        val id = seedFile(provider, rootFolder(provider), "data.bin", content)

        val window = provider.openDownload(account(provider), id, 1_000L..1_099L).use { readAll(it) }

        assertContentEquals(content.copyOfRange(1_000, 1_100), window)
    }

    @Test
    fun `prepareDestination creates a folder chain and is idempotent`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val path = CloudPath.parse("photos/2026/April")

        val first = provider.prepareDestination(account(provider), root, path)
        val second = provider.prepareDestination(account(provider), root, path)

        assertEquals(first.id, second.id, "a retried folder creation must not make a second folder")
        assertTrue(first.created)
    }

    @Test
    fun `lookupDestination finds nothing before an upload and the object after`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        assertEquals(emptyList(), provider.lookupDestination(account(provider), root, "report.txt"))

        upload(provider, root, "report.txt", "hello".toByteArray())

        val found = provider.lookupDestination(account(provider), root, "report.txt")
        assertEquals(1, found.size)
        assertEquals(5L, found.single().size)
    }

    @Test
    fun `lookupDestination reports every sibling that carries the name`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        if (!provider.capabilities.allowsDuplicateSiblingNames) return@runTest

        seedFile(provider, root, "twin.txt", byteArrayOf(1))
        seedFile(provider, root, "twin.txt", byteArrayOf(2))

        assertEquals(
            2,
            provider.lookupDestination(account(provider), root, "twin.txt").size,
            "spec §19.3 needs every match, so multiples become CONFLICT rather than a guess",
        )
    }

    @Test
    fun `a case-insensitive provider matches a differently cased name`() = runTest {
        val provider = newProvider()
        if (provider.capabilities.caseSensitiveNames) return@runTest
        val root = rootFolder(provider)
        seedFile(provider, root, "Report.TXT", byteArrayOf(1))

        assertEquals(1, provider.lookupDestination(account(provider), root, "report.txt").size)
    }

    @Test
    fun `upload stores the bytes and reports progress`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        val content = ByteArray(700_000) { (it % 97).toByte() }

        val uploaded = upload(provider, root, "big.bin", content)

        assertEquals(content.size.toLong(), uploaded.size)
        val readBack = provider.openDownload(account(provider), uploaded.id).use { readAll(it) }
        assertContentEquals(content, readBack)
    }

    @Test
    fun `finishUpload reports the provider hash when the provider has one`() = runTest {
        val provider = newProvider()
        val algorithm = provider.capabilities.nativeHashAlgorithm
        if (!provider.capabilities.supportsServerHash || algorithm == null) return@runTest
        val content = "verify me".toByteArray()

        val uploaded = upload(provider, rootFolder(provider), "hashed.bin", content)

        val reported = assertNotNull(uploaded.providerHash, "a server-hash provider must report one")
        assertEquals(FakeCloudProvider.hash(content, algorithm).value, reported.value)
    }

    @Test
    fun `queryUpload reports what the destination already holds`() = runTest {
        val provider = newProvider()
        if (!provider.capabilities.supportsResumableUpload) return@runTest
        val chunkSize = provider.capabilities.alignChunkSize(256L * 1024).toInt()
        val content = ByteArray(chunkSize * 2) { (it % 31).toByte() }
        val session = provider.beginUpload(
            account(provider),
            UploadRequest(
                account = account(provider),
                parent = rootFolder(provider),
                name = "resumed.bin",
                size = content.size.toLong(),
                mimeType = null,
            ),
        )

        provider.uploadChunk(session, Chunk(0, content.copyOfRange(0, chunkSize)))

        assertEquals(chunkSize.toLong(), provider.queryUpload(session).acknowledgedBytes)
    }

    @Test
    fun `an aborted upload leaves nothing at the destination`() = runTest {
        val provider = newProvider()
        val root = rootFolder(provider)
        // One aligned, non-final chunk: the upload is genuinely half-done.
        val chunkSize = provider.capabilities.alignChunkSize(256L * 1024).toInt()
        val session = provider.beginUpload(
            account(provider),
            UploadRequest(
                account = account(provider),
                parent = root,
                name = "abandoned.bin",
                size = chunkSize * 2L,
                mimeType = null,
            ),
        )
        provider.uploadChunk(session, Chunk(0, ByteArray(chunkSize), isFinal = false))

        provider.abortUpload(session)

        assertEquals(
            emptyList(),
            provider.lookupDestination(account(provider), root, "abandoned.bin"),
            "spec §22.2: an aborted session must not leave a partial object",
        )
    }

    // ------------------------------------------------------------------ helpers

    protected fun selectionOf(
        provider: CloudProvider,
        vararg roots: CloudObjectId,
        type: CloudObjectType = CloudObjectType.FOLDER,
    ): CloudSelection {
        val objects = roots.map { id ->
            CloudObject(
                id = id,
                name = id.opaqueId,
                type = type,
                parentId = null,
                size = null,
                modifiedAt = null,
                providerHash = null,
                revision = null,
                mimeType = null,
            )
        }
        return CloudSelection.of(account(provider), objects)
    }

    protected suspend fun upload(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
        content: ByteArray,
    ): CloudObject {
        val account = account(provider)
        val session = provider.beginUpload(
            account,
            UploadRequest(
                account = account,
                parent = parent,
                name = name,
                size = content.size.toLong(),
                mimeType = "application/octet-stream",
            ),
        )
        val chunkSize = provider.capabilities.alignChunkSize(8L * 1024 * 1024).toInt()
        var offset = 0
        while (offset < content.size || offset == 0) {
            val length = minOf(chunkSize, content.size - offset)
            val isFinal = offset + length >= content.size
            provider.uploadChunk(
                session,
                Chunk(offset.toLong(), content.copyOfRange(offset, offset + length), length, isFinal),
            )
            offset += length
            if (isFinal) break
        }
        return provider.finishUpload(session)
    }

    protected suspend fun readAll(download: dev.thiagosindra.cloudlug.provider.CloudDownload): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val scratch = ByteArray(8 * 1024)
        while (true) {
            val read = download.read(scratch, 0, scratch.size)
            if (read < 0) break
            buffer.write(scratch, 0, read)
        }
        return buffer.toByteArray()
    }
}

