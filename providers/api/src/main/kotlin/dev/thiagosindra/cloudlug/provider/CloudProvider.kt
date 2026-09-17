package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderType
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the transfer engine and any cloud service (spec §5).
 *
 * The engine knows only that bytes move from one [CloudProvider] to another
 * (spec §35). Everything a provider does differently is expressed through
 * [capabilities] or translated into [CloudException]; adding OneDrive, Box,
 * WebDAV or S3 later must not require touching `:core:transfer` (spec §32.7).
 *
 * Implementations must be safe to call from multiple coroutines and must throw
 * [CloudException] rather than provider-specific exception types.
 */
interface CloudProvider {
    val type: ProviderType

    val capabilities: ProviderCapabilities

    suspend fun authenticate(): CloudAccount

    suspend fun disconnect(account: AccountId)

    /**
     * Fetches current metadata for one object. Called immediately before
     * [openDownload] so a source that changed after the user reviewed the
     * manifest is caught (spec §20.6).
     */
    suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject

    /**
     * Walks [selection] depth-first, emitting every descendant including empty
     * folders (spec §20.5). Enumeration is paged internally and may be resumed
     * from [CloudSelection.resumeCursor] (spec §11).
     */
    suspend fun enumerate(account: AccountId, selection: CloudSelection): Flow<CloudObject>

    /** Null when the provider does not report quota (spec §20.7). */
    suspend fun quota(account: AccountId): StorageQuota?

    /**
     * Opens a read stream. [range] is honoured only when
     * [ProviderCapabilities.supportsRangeDownload] is true.
     */
    suspend fun openDownload(
        account: AccountId,
        objectId: CloudObjectId,
        range: LongRange? = null,
    ): CloudDownload

    /**
     * Creates (or finds) the folder chain [relativePath] under [parent].
     * Creating folders must be idempotent: a retry after process death must not
     * produce a second folder.
     */
    suspend fun prepareDestination(
        account: AccountId,
        parent: CloudObjectId,
        relativePath: CloudPath,
    ): DestinationObject

    /**
     * Returns every sibling of [parent] named [name]. A list, because some
     * providers allow duplicate sibling names (spec §5, §20.3) — more than one
     * match is a `CONFLICT`, never a guess (spec §19.3).
     */
    suspend fun lookupDestination(
        account: AccountId,
        parent: CloudObjectId,
        name: String,
    ): List<CloudObject>

    suspend fun beginUpload(account: AccountId, request: UploadRequest): UploadSession

    suspend fun uploadChunk(session: UploadSession, chunk: Chunk): UploadProgress

    /** Asks the provider how much it already holds, after a crash or reconnect. */
    suspend fun queryUpload(session: UploadSession): UploadProgress

    /**
     * Commits the upload. The returned object should carry the provider's
     * native hash where available, so verification costs no extra request
     * (spec §21).
     */
    suspend fun finishUpload(session: UploadSession): CloudObject

    suspend fun abortUpload(session: UploadSession)
}

/**
 * What a provider can and cannot do (spec §5).
 *
 * This is the engine's only source of provider knowledge. A `when` on
 * [ProviderType] inside `:core:transfer` is a bug; a missing capability flag is
 * the fix.
 */
data class ProviderCapabilities(
    /** False when this build has no read scope for the provider (spec §8.2). */
    val canBeSource: Boolean,
    val canBeDestination: Boolean,
    val supportsRangeDownload: Boolean,
    val supportsResumableUpload: Boolean,
    val supportsServerHash: Boolean,
    /** Hash the destination reports, and therefore the one computed locally (spec §19.4). */
    val nativeHashAlgorithm: HashAlgorithm?,
    val supportsFolderPicker: Boolean,
    val supportsMultipleSourceSelection: Boolean,
    val supportsStableObjectIds: Boolean,
    val supportsModifiedTimeWrite: Boolean,
    val supportsCustomMetadata: Boolean,
    val caseSensitiveNames: Boolean,
    val allowsDuplicateSiblingNames: Boolean,
    /** Upload chunk sizes must be a multiple of this many bytes. */
    val uploadChunkAlignment: Long,
    val maxUploadChunkBytes: Long,
    val illegalNameCharacters: Set<Char>,
    val maxNameLength: Int,
) {
    init {
        require(uploadChunkAlignment > 0) { "uploadChunkAlignment must be positive" }
        require(maxUploadChunkBytes >= uploadChunkAlignment) {
            "maxUploadChunkBytes must be at least one alignment unit"
        }
        require(maxNameLength > 0) { "maxNameLength must be positive" }
        require(!supportsServerHash || nativeHashAlgorithm != null) {
            "A provider that declares supportsServerHash must name its hash algorithm"
        }
    }

    /**
     * Largest chunk size not exceeding [preferred] that satisfies this
     * provider's alignment. Returns [uploadChunkAlignment] when [preferred] is
     * smaller than one aligned unit.
     */
    fun alignChunkSize(preferred: Long): Long {
        val capped = minOf(preferred, maxUploadChunkBytes)
        val aligned = capped - (capped % uploadChunkAlignment)
        return if (aligned <= 0) uploadChunkAlignment else aligned
    }
}
