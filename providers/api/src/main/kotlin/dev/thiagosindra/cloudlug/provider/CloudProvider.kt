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
     * folders (spec §20.5).
     *
     * Not `suspend`: returning a cold [Flow] does not suspend, and v1.2 §5
     * removed the modifier the v1.1 signature carried (ADR-0015).
     *
     * [resumeAfter] is the ID of the last object the caller persisted, so a walk
     * of a large tree survives process death (spec §11). An adapter that cannot
     * resume from an object ID may restart from the beginning; the manifest
     * builder deduplicates by source object ID, so a restart is idempotent,
     * only slower.
     */
    fun enumerate(
        account: AccountId,
        selection: CloudSelection,
        resumeAfter: CloudObjectId? = null,
    ): Flow<CloudObject>

    /**
     * One level of children under [parent], for the in-app browser §9 calls for.
     *
     * [enumerate] cannot serve a picker: it walks depth-first through the whole
     * subtree, so opening an account root would enumerate everything in it
     * before a single row could be drawn. A picker needs one level at a time,
     * and [lookupDestination] only answers "what is called this?". Added in
     * v0.2 when the wizard needed it — see docs/decisions.md ADR-0024.
     *
     * Ordering is the provider's own. Implementations page internally and emit
     * as pages arrive, so a folder with thousands of children renders
     * progressively.
     */
    fun listChildren(account: AccountId, parent: CloudObjectId): Flow<CloudObject>

    /**
     * The account's root, and the only [CloudObjectId] a caller can name
     * without having discovered it from a previous call.
     *
     * [listChildren] takes an ID but had no way to produce the first one, so
     * the wizard invented the literal `"root"` and the fake stored its
     * top-level objects under `null`. They never matched, and the picker was
     * empty for every provider — see docs/decisions.md ADR-0026.
     *
     * Root is spelled differently everywhere (Dropbox uses the empty string,
     * Drive the literal `"root"`), so only the adapter can answer this. It
     * takes an [AccountId] because a provider may expose more than one root
     * per account, such as a personal and a team space.
     */
    fun rootOf(account: AccountId): CloudObjectId

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
    /**
     * Longest full path the provider accepts, or null when it has no such limit
     * (spec §5, §20.4). Added in v1.2; ADR-0013 deferred it for want of a
     * capability flag.
     */
    val maxPathLength: Int?,
    /** True when the provider rejects names ending in a space or a dot (spec §20.4). */
    val disallowsTrailingSpaceOrDot: Boolean,
) {
    init {
        require(uploadChunkAlignment > 0) { "uploadChunkAlignment must be positive" }
        require(maxUploadChunkBytes >= uploadChunkAlignment) {
            "maxUploadChunkBytes must be at least one alignment unit"
        }
        require(maxNameLength > 0) { "maxNameLength must be positive" }
        require(maxPathLength == null || maxPathLength >= maxNameLength) {
            "maxPathLength must leave room for at least one maximum-length name"
        }
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
