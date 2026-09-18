package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderCapabilitiesTest {

    private fun capabilities(
        alignment: Long = 256L * 1024,
        maxChunk: Long = 64L * 1024 * 1024,
        serverHash: Boolean = true,
        hash: HashAlgorithm? = HashAlgorithm.MD5,
    ) = ProviderCapabilities(
        canBeSource = true,
        canBeDestination = true,
        supportsRangeDownload = true,
        supportsResumableUpload = true,
        supportsServerHash = serverHash,
        nativeHashAlgorithm = hash,
        supportsFolderPicker = true,
        supportsMultipleSourceSelection = true,
        supportsStableObjectIds = true,
        supportsModifiedTimeWrite = true,
        supportsCustomMetadata = false,
        caseSensitiveNames = true,
        allowsDuplicateSiblingNames = false,
        uploadChunkAlignment = alignment,
        maxUploadChunkBytes = maxChunk,
        illegalNameCharacters = setOf('/'),
        maxNameLength = 255,
        maxPathLength = null,
        disallowsTrailingSpaceOrDot = false,
    )

    @Test
    fun `chunk size is rounded down to the provider alignment`() {
        // 8 MiB is already a multiple of Drive's 256 KiB granularity (spec §15).
        assertEquals(8L * 1024 * 1024, capabilities().alignChunkSize(8L * 1024 * 1024))
        assertEquals(256L * 1024, capabilities().alignChunkSize(300L * 1024))
    }

    @Test
    fun `chunk size never exceeds the provider maximum`() {
        val caps = capabilities(maxChunk = 4L * 1024 * 1024)
        assertEquals(4L * 1024 * 1024, caps.alignChunkSize(64L * 1024 * 1024))
    }

    @Test
    fun `a preferred size below one alignment unit yields one unit`() {
        assertEquals(256L * 1024, capabilities().alignChunkSize(1024))
    }

    @Test
    fun `declaring a server hash without naming the algorithm is rejected`() {
        assertFailsWith<IllegalArgumentException> { capabilities(serverHash = true, hash = null) }
    }
}
