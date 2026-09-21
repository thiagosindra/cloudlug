package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities

/**
 * What Dropbox can and cannot do, declared truthfully (spec §5, §32.7).
 *
 * The engine has no Dropbox-shaped branches anywhere; every difference between
 * providers arrives through this object. That makes an over-claim here worse
 * than a missing feature: the engine will act on it, and the failure surfaces
 * somewhere far from the lie. Each value below says why it is what it is.
 */
object DropboxCapabilities {

    /** 4 MiB, the block size Dropbox's `content_hash` is defined over (§19.4). */
    const val BLOCK_BYTES = 4L * 1024 * 1024

    /**
     * 8 MiB per uploaded chunk.
     *
     * Dropbox permits up to 150 MB in one `append_v2` call, but the useful
     * bound is not the maximum: a chunk is the unit that has to be re-sent
     * after a dropped connection and the unit held in the §15 cache. 8 MiB is
     * two whole hash blocks, so a chunk boundary is always a block boundary and
     * the checkpointed hasher never has a partial block spanning a retry.
     */
    const val UPLOAD_CHUNK_BYTES = 8L * 1024 * 1024

    val Default: ProviderCapabilities = ProviderCapabilities(
        canBeSource = true,
        canBeDestination = true,

        // `/2/files/download` honours a Range header, which §21 needs to resume
        // a partly cached object rather than restart it.
        supportsRangeDownload = true,

        // upload_session/start, append_v2, finish (§22.5).
        supportsResumableUpload = true,

        // Every FileMetadata carries content_hash, and §36's validation against
        // a live account confirmed it agrees with core:hashing — including at
        // the 4 MiB boundary and for a file this project never uploaded.
        supportsServerHash = true,
        nativeHashAlgorithm = HashAlgorithm.DROPBOX_CONTENT_HASH,

        supportsFolderPicker = true,
        supportsMultipleSourceSelection = true,

        // Dropbox ids ("id:AbC123") survive a move or rename, which is what
        // §11 needs to resume an enumeration after process death.
        supportsStableObjectIds = true,

        // client_modified on upload.
        supportsModifiedTimeWrite = true,

        // No user-defined properties without the file_properties scope, which
        // §8.2 does not request.
        supportsCustomMetadata = false,

        // Dropbox preserves the case you give it but resolves case-insensitively,
        // so "Photo.JPG" and "photo.jpg" are the same object. §19.3 needs this
        // to decide whether a destination name collides.
        caseSensitiveNames = false,
        allowsDuplicateSiblingNames = false,

        uploadChunkAlignment = BLOCK_BYTES,
        maxUploadChunkBytes = 150L * 1024 * 1024,

        // Dropbox rejects these outright in a path component.
        illegalNameCharacters = setOf('/', '\\', ':', '?', '*', '"', '<', '>', '|'),

        maxNameLength = 255,

        // Dropbox's documented ceiling for a full path.
        maxPathLength = 260,

        // A trailing space or dot is rejected, so §19.3's disambiguating suffix
        // must never leave one behind.
        disallowsTrailingSpaceOrDot = true,
    )
}
