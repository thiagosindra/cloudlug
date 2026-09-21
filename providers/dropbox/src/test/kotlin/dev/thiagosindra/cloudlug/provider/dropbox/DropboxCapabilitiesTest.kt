package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Capabilities are promises the engine acts on without checking, so these
 * assert the ones that would cause silent damage if they were wrong, not the
 * ones that would merely disable a feature.
 */
class DropboxCapabilitiesTest {

    private val capabilities = DropboxCapabilities.Default

    @Test
    fun `a chunk boundary is always a hash block boundary`() {
        // §19.4 checkpoints the block hash. If a chunk could end mid-block, a
        // resumed upload would have to re-read bytes it already hashed, and the
        // checkpoint would have to carry a partial block across the gap.
        assertEquals(0, DropboxCapabilities.UPLOAD_CHUNK_BYTES % DropboxCapabilities.BLOCK_BYTES)
        assertEquals(DropboxCapabilities.BLOCK_BYTES, capabilities.uploadChunkAlignment)
    }

    @Test
    fun `the negotiated chunk size stays aligned and within the provider limit`() {
        val chosen = capabilities.alignChunkSize(DropboxCapabilities.UPLOAD_CHUNK_BYTES)
        assertEquals(DropboxCapabilities.UPLOAD_CHUNK_BYTES, chosen)
        assertTrue(chosen <= capabilities.maxUploadChunkBytes)

        // Even an absurd preference comes back aligned rather than truncated to
        // something Dropbox would reject.
        val capped = capabilities.alignChunkSize(Long.MAX_VALUE)
        assertEquals(0, capped % capabilities.uploadChunkAlignment)
        assertTrue(capped <= capabilities.maxUploadChunkBytes)
    }

    @Test
    fun `names are case-insensitive, which decides collisions`() {
        // §19.3: getting this wrong means CloudLug uploads "Photo.JPG" beside
        // "photo.jpg" believing both exist, and Dropbox overwrites one.
        assertFalse(capabilities.caseSensitiveNames)
        assertFalse(capabilities.allowsDuplicateSiblingNames)
    }

    @Test
    fun `the server hash is the one §36 validated against a live account`() {
        assertTrue(capabilities.supportsServerHash)
        assertEquals(HashAlgorithm.DROPBOX_CONTENT_HASH, capabilities.nativeHashAlgorithm)
    }

    @Test
    fun `declaring a server hash without naming an algorithm would be incoherent`() {
        // §21 refuses to complete an item it cannot verify. A provider that
        // claims a hash and names none leaves every item unverifiable.
        assertTrue(!capabilities.supportsServerHash || capabilities.nativeHashAlgorithm != null)
    }

    @Test
    fun `custom metadata is not claimed, because the scope for it was never asked for`() {
        // §8.2 requests four scopes and file_properties is not among them.
        assertFalse(capabilities.supportsCustomMetadata)
        assertFalse("file_properties" in DropboxOAuth.SCOPES.joinToString(" "))
    }

    @Test
    fun `a path separator is never a legal name character`() {
        assertTrue('/' in capabilities.illegalNameCharacters)
        assertTrue('\\' in capabilities.illegalNameCharacters)
        assertTrue(capabilities.disallowsTrailingSpaceOrDot)
    }
}
