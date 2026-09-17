package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationTest {

    private val expected = ProviderHash(HashAlgorithm.MD5, "abc123")
    private val different = ProviderHash(HashAlgorithm.MD5, "def456")

    private fun uploaded(size: Long? = 100, hash: ProviderHash? = null) = CloudObject(
        id = CloudObjectId(ProviderType.FAKE, "dst-1"),
        name = "report.txt",
        type = CloudObjectType.FILE,
        parentId = null,
        size = size,
        modifiedAt = null,
        providerHash = hash,
        revision = null,
        mimeType = null,
    )

    private val hashingProvider = FakeCloudProvider.defaultCapabilities(nativeHashAlgorithm = HashAlgorithm.MD5)
    private val hashlessProvider =
        FakeCloudProvider.defaultCapabilities(nativeHashAlgorithm = null, supportsServerHash = false)

    @Test
    fun `step 1 - a matching hash in the finish response verifies`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(hash = expected),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { error("step 2 must not run when the finish response carries a hash") },
        )
        assertEquals(
            ItemStatusReason.VERIFIED_BY_DESTINATION_HASH,
            assertIs<VerificationResult.Verified>(result).reason,
        )
    }

    @Test
    fun `step 2 - a missing hash is fetched with one metadata call, not a re-download`() = runTest {
        var metadataCalls = 0
        val result = DestinationVerifier.verify(
            uploaded = uploaded(hash = null),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = {
                metadataCalls++
                uploaded(hash = expected)
            },
        )
        assertIs<VerificationResult.Verified>(result)
        assertEquals(1, metadataCalls)
    }

    @Test
    fun `a differing hash is a mismatch, not a pass`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(hash = different),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { uploaded(hash = different) },
        )
        assertIs<VerificationResult.Mismatch>(result)
    }

    @Test
    fun `a size mismatch fails even when a hash would have matched`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(size = 99, hash = expected),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { uploaded(size = 99, hash = expected) },
        )
        assertTrue(assertIs<VerificationResult.Mismatch>(result).detail.contains("99"))
    }

    @Test
    fun `step 4 - size-only verification is allowed where the provider has no server hash`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(size = 100),
            expectedNativeHash = null,
            expectedSize = 100,
            capabilities = hashlessProvider,
            resolveMetadata = { error("no hash to fetch") },
        )
        assertEquals(
            ItemStatusReason.VERIFIED_BY_SIZE_ONLY,
            assertIs<VerificationResult.Verified>(result).reason,
            "spec §21 step 4 flags these in history",
        )
    }

    @Test
    fun `a hashing provider that reports no hash is unverifiable, not verified by size`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(size = 100, hash = null),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { uploaded(size = 100, hash = null) },
        )
        assertIs<VerificationResult.Unverifiable>(result)
    }

    @Test
    fun `a successful upload response alone never verifies on a hashing provider`() = runTest {
        // Spec §21: "A successful upload API response is not by itself sufficient".
        val result = DestinationVerifier.verify(
            uploaded = uploaded(size = null, hash = null),
            expectedNativeHash = null,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { uploaded(size = null, hash = null) },
        )
        assertIs<VerificationResult.Unverifiable>(result)
    }

    @Test
    fun `an algorithm mismatch is unverifiable rather than a failure`() = runTest {
        val result = DestinationVerifier.verify(
            uploaded = uploaded(hash = ProviderHash(HashAlgorithm.SHA1, "abc123")),
            expectedNativeHash = expected,
            expectedSize = 100,
            capabilities = hashingProvider,
            resolveMetadata = { uploaded(hash = ProviderHash(HashAlgorithm.SHA1, "abc123")) },
        )
        assertIs<VerificationResult.Unverifiable>(result)
    }
}
