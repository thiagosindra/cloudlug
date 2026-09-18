package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DestinationCollisionResolverTest {

    private fun destinationObject(
        name: String = "report.txt",
        size: Long? = 100,
        hash: ProviderHash? = null,
    ) = CloudObject(
        id = CloudObjectId(ProviderType.FAKE, "dst-$name"),
        name = name,
        type = CloudObjectType.FILE,
        parentId = null,
        size = size,
        modifiedAt = null,
        providerHash = hash,
        revision = null,
        mimeType = null,
    )

    private val md5 = ProviderHash(HashAlgorithm.MD5, "abc123")
    private val otherMd5 = ProviderHash(HashAlgorithm.MD5, "def456")
    private val dropboxHash = ProviderHash(HashAlgorithm.DROPBOX_CONTENT_HASH, "abc123")

    @Test
    fun `no match uploads`() {
        assertEquals(
            CollisionOutcome.Upload,
            DestinationCollisionResolver.resolve(emptyList(), sourceSize = 100, comparableHashes = emptyList()),
        )
    }

    @Test
    fun `multiple matches are a conflict and never a guess`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(), destinationObject()),
            sourceSize = 100,
            comparableHashes = listOf(md5),
        )
        assertEquals(
            ItemStatusReason.CONFLICT_MULTIPLE_MATCHES,
            assertIs<CollisionOutcome.Conflict>(outcome).reason,
        )
    }

    @Test
    fun `a different size is a conflict`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(size = 99, hash = md5)),
            sourceSize = 100,
            comparableHashes = listOf(md5),
        )
        assertEquals(ItemStatusReason.CONFLICT_SIZE_DIFFERS, assertIs<CollisionOutcome.Conflict>(outcome).reason)
    }

    @Test
    fun `equal size and equal strong hash is a duplicate`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = md5)),
            sourceSize = 100,
            comparableHashes = listOf(md5),
        )
        assertEquals(
            ItemStatusReason.DUPLICATE_VERIFIED_BY_HASH,
            assertIs<CollisionOutcome.SkipDuplicate>(outcome).reason,
        )
    }

    @Test
    fun `equal size and differing strong hash is a conflict`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = otherMd5)),
            sourceSize = 100,
            comparableHashes = listOf(md5),
        )
        assertEquals(ItemStatusReason.CONFLICT_HASH_DIFFERS, assertIs<CollisionOutcome.Conflict>(outcome).reason)
    }

    @Test
    fun `equal size with no hash at the destination is a conflict`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = null)),
            sourceSize = 100,
            comparableHashes = listOf(md5),
        )
        assertEquals(
            ItemStatusReason.CONFLICT_NO_COMPARABLE_HASH,
            assertIs<CollisionOutcome.Conflict>(outcome).reason,
        )
    }

    @Test
    fun `hashes of different algorithms do not make a duplicate`() {
        // Dropbox content_hash and Drive MD5 say nothing about each other
        // (spec §19.4), even when the hex happens to be identical.
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = md5)),
            sourceSize = 100,
            comparableHashes = listOf(dropboxHash),
        )
        assertEquals(
            ItemStatusReason.CONFLICT_NO_COMPARABLE_HASH,
            assertIs<CollisionOutcome.Conflict>(outcome).reason,
        )
    }

    @Test
    fun `an unknown source size cannot prove equality`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = md5)),
            sourceSize = null,
            comparableHashes = listOf(md5),
        )
        assertEquals(ItemStatusReason.CONFLICT_SIZE_DIFFERS, assertIs<CollisionOutcome.Conflict>(outcome).reason)
    }

    @Test
    fun `the first comparable hash is used when several are held`() {
        val outcome = DestinationCollisionResolver.resolve(
            matches = listOf(destinationObject(hash = md5)),
            sourceSize = 100,
            comparableHashes = listOf(dropboxHash, ProviderHash(HashAlgorithm.SHA256, "aaaa"), md5),
        )
        assertIs<CollisionOutcome.SkipDuplicate>(outcome)
    }
}
