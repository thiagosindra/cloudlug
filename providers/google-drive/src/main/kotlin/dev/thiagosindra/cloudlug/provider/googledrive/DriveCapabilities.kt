package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities

/**
 * What Google Drive can and cannot do, declared truthfully (spec §5, §32.7).
 *
 * As with Dropbox, an over-claim here is worse than a missing feature: the
 * engine acts on every value, and the failure surfaces far from the lie.
 */
object DriveCapabilities {

    /** Drive's resumable-upload granularity: every non-final chunk is a multiple of this. */
    const val CHUNK_ALIGNMENT = 256L * 1024

    /**
     * 8 MiB per chunk, the same as Dropbox (§15).
     *
     * Drive accepts larger chunks, but the chunk is the unit re-sent after a
     * dropped connection and the unit held in the cache, and 8 MiB is both a
     * multiple of 256 KiB and of Dropbox's 4 MiB hash block, so one size serves
     * both ends of a Dropbox → Drive transfer.
     */
    const val UPLOAD_CHUNK_BYTES = 8L * 1024 * 1024

    /**
     * Capabilities for a build that requests [requestedScopes].
     *
     * §8.2 item 3: `canBeSource` follows the scopes, so the same adapter is a
     * destination-only provider in the Play build (`drive.file`) and a source
     * as well in a self-build that asks for `drive.readonly`. These are the
     * build's capabilities; what one *account* may do is
     * [GoogleOAuth.rolesFor] over what that account actually granted (§7).
     */
    fun forScopes(requestedScopes: Collection<String>): ProviderCapabilities {
        val roles = GoogleOAuth.rolesFor(requestedScopes.toSet())
        return ProviderCapabilities(
            canBeSource = roles.canBeSource,
            canBeDestination = roles.canBeDestination,

            // `alt=media` honours a Range header.
            supportsRangeDownload = true,

            // uploadType=resumable: a session URI, 308 with Range, and a status
            // query, all captured.
            supportsResumableUpload = true,

            // md5Checksum on every binary file. Both it and sha256Checksum were
            // returned in the response that finished the captured upload, and
            // both matched the bytes sent.
            supportsServerHash = true,
            nativeHashAlgorithm = HashAlgorithm.MD5,

            supportsFolderPicker = true,
            supportsMultipleSourceSelection = true,

            // A Drive file id survives a move and a rename.
            supportsStableObjectIds = true,

            // `modifiedTime` is writable in the metadata that starts an upload.
            supportsModifiedTimeWrite = true,

            // appProperties exist, but nothing here writes them.
            supportsCustomMetadata = false,

            // §20.3: Drive is case-sensitive and permits same-name siblings, so
            // a lookup can return several objects and §19.3 makes that CONFLICT.
            caseSensitiveNames = true,
            allowsDuplicateSiblingNames = true,

            uploadChunkAlignment = CHUNK_ALIGNMENT,
            maxUploadChunkBytes = UPLOAD_CHUNK_BYTES,

            // A Drive name is a property, not a path segment: `/` is legal in it.
            illegalNameCharacters = emptySet(),

            // Drive documents no limit. This is far above anything a Dropbox
            // name (255) can bring, which is the only source v0.6 has.
            maxNameLength = 32_767,

            // There are no paths in Drive, only parents.
            maxPathLength = null,
            disallowsTrailingSpaceOrDot = false,
        )
    }

    /** This build: `drive.file` only, so a destination and never a source. */
    val Default: ProviderCapabilities = forScopes(GoogleOAuth.SCOPES)
}
