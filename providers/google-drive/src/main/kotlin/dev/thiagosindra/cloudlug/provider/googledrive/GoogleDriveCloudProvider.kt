package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.network.StreamingRequestBody
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudDownload
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.DestinationObject
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities
import dev.thiagosindra.cloudlug.provider.StorageQuota
import dev.thiagosindra.cloudlug.provider.UploadProgress
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.UploadSession
import dev.thiagosindra.cloudlug.provider.googledrive.DriveObjects.text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Drive as a [CloudProvider] (spec §5; v0.6 as a destination).
 *
 * Everything Drive-specific stops here (§32.7). Two things set it apart from
 * Dropbox, and both are declared in [capabilities] rather than special-cased:
 * names are case-sensitive and siblings may share one (§20.3), and in this
 * build it is a destination only.
 *
 * ### Destination only, honestly
 *
 * `drive.file` (§8.2) can read what CloudLug created, so [enumerate] and
 * [openDownload] *could* answer for those few objects — and would then answer
 * an empty or partial walk for everything else, which the engine cannot tell
 * from a small selection. They refuse instead, naming the missing scope, unless
 * the account granted `drive.readonly` or `drive` (§8.2 item 3), so the same
 * adapter serves a self-build without a code change.
 *
 * ### The resumable session
 *
 * [beginUpload] returns a session whose [UploadSession.providerMetadata] is
 * the session URI Drive handed back. It is the whole session — the engine
 * persists it (§12.2) and every later call, after process death included, is
 * a PUT to it. [UploadSession.expiresAt] is set a day inside Google's
 * documented week, so §22.5 abandons a session before Google does.
 */
class GoogleDriveCloudProvider(
    private val tokens: DriveTokenSource,
    client: OkHttpClient = OkHttpClient(),
    override val capabilities: ProviderCapabilities = DriveCapabilities.Default,
    private val clock: () -> Instant = Instant::now,
) : CloudProvider {

    override val type: ProviderType = ProviderType.GOOGLE_DRIVE

    private val api = DriveApi(tokens, client)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The file a session produced, kept between the final chunk and
     * [finishUpload]. Drive answers the final chunk with the file itself, so
     * there is nothing left to commit; after process death the map is empty
     * and [finishUpload] asks the session instead, which answers a completed
     * upload with the same file (captured as `upload_status_after_finish`).
     */
    private val finished = ConcurrentHashMap<String, CloudObject>()

    /**
     * §8.1's connect-time call. Google's grant names no account, so this asks
     * `about.get` with the pending grant's token and files the credential under
     * the answer. `permissionId` is the user's stable id across Drive.
     */
    override suspend fun authenticate(): CloudAccount {
        val about = api.about(ABOUT_USER, tokens.pendingAccessToken())
        val user = about["user"] as? JsonObject
            ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive returned no user")
        val id = AccountId(
            user.text("permissionId")
                ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive returned no permissionId"),
        )
        tokens.bindPending(id)
        return CloudAccount(
            id = id,
            provider = type,
            displayName = user.text("displayName"),
            displayEmail = user.text("emailAddress"),
            grantedScopes = tokens.grantedScopes(id),
        )
    }

    /** §8.3: revoke at Google. Clearing the stored credential is the connector's, after this succeeds. */
    override suspend fun disconnect(account: AccountId) = tokens.revoke(account)

    override fun rootOf(account: AccountId): CloudObjectId = DriveObjects.idOf(DriveObjects.ROOT)

    /**
     * Works under `drive.file` for anything CloudLug created, which is every
     * object §21 step 2 will ever ask about.
     */
    override suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject =
        DriveObjects.toCloudObject(api.getFile(account, objectId.opaqueId), parent = null)
            ?: throw CloudException(CloudErrorKind.NOT_FOUND, "the object is in the Google Drive trash")

    /**
     * One level, paged as Drive pages it. Under `drive.file` that is My Drive's
     * top level plus whatever CloudLug created — the constraint
     * spec-proposals/v1.6.md §2 puts on the destination picker.
     */
    override fun listChildren(account: AccountId, parent: CloudObjectId): Flow<CloudObject> = flow {
        var pageToken: String? = null
        do {
            val page = api.list(account, "${DriveApi.literal(parent.opaqueId)} in parents and trashed = false", pageToken)
            (page["files"] as? JsonArray).orEmpty().forEach { element ->
                DriveObjects.toCloudObject(element.jsonObject, parent)?.let { emit(it) }
            }
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
    }

    /**
     * §11's walk, depth-first with the roots emitted, the same shape as
     * Dropbox's — offered only to an account that granted a read scope.
     */
    override fun enumerate(
        account: AccountId,
        selection: CloudSelection,
        resumeAfter: CloudObjectId?,
    ): Flow<CloudObject> = flow {
        requireSource(account)
        var skipping = resumeAfter != null && runCatching { resolveMetadata(account, resumeAfter) }.isSuccess
        val pending = ArrayDeque(selection.roots.map { it.cloudObject }.asReversed())
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (skipping) {
                if (current.id == resumeAfter) skipping = false
            } else {
                emit(current)
            }
            if (current.type != CloudObjectType.FOLDER) continue
            listChildren(account, current.id).toList().asReversed().forEach(pending::addLast)
        }
    }

    /**
     * §20.7 from `storageQuota`. Drive omits `limit` for an account with no
     * limit, and §20.7 would rather report no quota than invent one.
     */
    override suspend fun quota(account: AccountId): StorageQuota? {
        val quota = api.about(account, "storageQuota")["storageQuota"] as? JsonObject ?: return null
        val limit = quota.text("limit")?.toLongOrNull() ?: return null
        val usage = quota.text("usage")?.toLongOrNull() ?: return null
        return StorageQuota(totalBytes = limit, usedBytes = usage, availableBytes = limit - usage)
    }

    override suspend fun openDownload(account: AccountId, objectId: CloudObjectId, range: LongRange?): CloudDownload {
        requireSource(account)
        val effective = range?.takeIf { capabilities.supportsRangeDownload }
        return DriveDownload(api.download(account, objectId.opaqueId, effective), effective)
    }

    /**
     * §10's enclosing folder and any missing ancestor of it, idempotently.
     *
     * Drive's create always creates — it would happily make a second folder of
     * the same name beside the first — so each segment is looked up first, and
     * a resumed transfer finds the folder it made before. Two folders already
     * sharing the name is refused rather than guessed between: putting files
     * in the wrong one is not recoverable from inside CloudLug (§2.5).
     */
    override suspend fun prepareDestination(
        account: AccountId,
        parent: CloudObjectId,
        relativePath: CloudPath,
    ): DestinationObject {
        var current = parent
        var created = false
        for (segment in relativePath.segments) {
            val existing = lookupDestination(account, current, segment).filter { it.type == CloudObjectType.FOLDER }
            current = when (existing.size) {
                0 -> {
                    created = true
                    DriveObjects.idOf(
                        api.createFolder(account, segment, current.opaqueId).text("id")
                            ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive created a folder and returned no id"),
                    )
                }
                1 -> existing.single().id
                else -> throw CloudException(
                    CloudErrorKind.PERMANENT,
                    "several folders at the destination share that name, so CloudLug cannot tell which is meant",
                    code = "duplicate_folder",
                )
            }
        }
        return DestinationObject(id = current, relativePath = relativePath, created = created)
    }

    /** §19.3: every sibling with that exact name — Drive is case-sensitive and permits several. */
    override suspend fun lookupDestination(account: AccountId, parent: CloudObjectId, name: String): List<CloudObject> {
        val query = "name = ${DriveApi.literal(name)} and ${DriveApi.literal(parent.opaqueId)} in parents and trashed = false"
        val matches = mutableListOf<CloudObject>()
        var pageToken: String? = null
        do {
            val page = api.list(account, query, pageToken)
            (page["files"] as? JsonArray).orEmpty().forEach { element ->
                DriveObjects.toCloudObject(element.jsonObject, parent)?.let(matches::add)
            }
            pageToken = page.text("nextPageToken")
        } while (pageToken != null)
        return matches
    }

    override suspend fun beginUpload(account: AccountId, request: UploadRequest): UploadSession {
        val modified = request.modifiedAt
            ?.takeIf { capabilities.supportsModifiedTimeWrite }
            ?.truncatedTo(ChronoUnit.MILLIS)
            ?.toString()
        val uri = api.initiateUpload(
            account,
            DriveApi.metadata(request.name, request.parent.opaqueId, null, modified),
            request.size,
            request.mimeType ?: OCTET,
        )
        return UploadSession(
            id = uploadIdOf(uri),
            request = request,
            providerMetadata = uri,
            expiresAt = clock().plus(SESSION_LIFETIME),
        )
    }

    /**
     * One chunk. A non-final chunk is answered `308` with a `Range` naming
     * every byte Drive holds, and that is what is reported as acknowledged —
     * never the offset this side believes, because §14 deletes cached chunks
     * on this number.
     *
     * If Drive took fewer bytes than were sent, the chunk is reported as a
     * transient failure, so the retry re-sends it whole. That is safe because
     * Drive accepts a chunk overlapping what it already holds (captured as
     * `upload_chunk_resent_from_zero`).
     */
    override suspend fun uploadChunk(session: UploadSession, chunk: Chunk): UploadProgress {
        require(chunk.isFinal || chunk.length.toLong() % capabilities.uploadChunkAlignment == 0L) {
            "a non-final chunk of ${chunk.length} bytes is not aligned to ${capabilities.uploadChunkAlignment}"
        }
        val total = when {
            chunk.isFinal -> chunk.endExclusive.toString()
            else -> session.request.size?.toString() ?: "*"
        }
        val range = if (chunk.length == 0) "bytes */$total" else "bytes ${chunk.offset}-${chunk.endExclusive - 1}/$total"
        val answer = api.putSession(
            session.request.account,
            sessionUri(session),
            range,
            // Streamed from the chunk's cache file: one buffer per chunk, not two.
            StreamingRequestBody(chunk.length.toLong(), open = chunk::openStream),
        )
        val progress = interpret(session, answer)
        if (!progress.complete && progress.acknowledgedBytes < chunk.endExclusive) {
            throw CloudException(
                CloudErrorKind.TRANSIENT_NETWORK,
                "Google Drive acknowledged ${progress.acknowledgedBytes} of ${chunk.endExclusive} bytes",
                code = "short_acknowledgement",
            )
        }
        return progress
    }

    /** §22.5's offset query: an empty PUT naming only the total. */
    override suspend fun queryUpload(session: UploadSession): UploadProgress {
        val total = session.request.size?.toString() ?: "*"
        val answer = api.putSession(session.request.account, sessionUri(session), "bytes */$total", ByteArray(0).toRequestBody(null))
        return interpret(session, answer)
    }

    /**
     * Nothing is committed here: the final chunk did that. This returns the
     * file it produced — from memory, or after process death from the session,
     * which answers a completed upload with the file. Both carry the checksums
     * asked for when the session began, which is §21 step 1.
     */
    override suspend fun finishUpload(session: UploadSession): CloudObject {
        finished.remove(session.id)?.let { return it }
        val progress = queryUpload(session)
        return finished.remove(session.id)?.takeIf { progress.complete }
            ?: throw CloudException(
                CloudErrorKind.PERMANENT,
                "the upload was finished before Google Drive held all of it",
                code = "upload_incomplete",
            )
    }

    /**
     * §22.2: cancel the session. Drive answers `499` once it has, and `404`
     * for one already gone; both are the outcome being asked for. Nothing is
     * left behind either way — a resumable upload creates no file until its
     * final byte arrives.
     */
    override suspend fun abortUpload(session: UploadSession) {
        finished.remove(session.id)
        val uri = session.providerMetadata ?: return
        val answer = api.cancelSession(session.request.account, uri)
        if (answer.status in 200..299 || answer.status == 499 || answer.status == 404) return
        throw DriveErrors.toException(answer.status, answer.body, DriveErrors.Surface.UPLOAD_SESSION, answer.retryAfter)
    }

    private fun interpret(session: UploadSession, answer: SessionAnswer): UploadProgress = when (answer.status) {
        308 -> UploadProgress(acknowledgedOf(answer.range))
        200, 201 -> {
            val file = runCatching { json.parseToJsonElement(answer.body).jsonObject }.getOrNull()
                ?.let { DriveObjects.toCloudObject(it, session.request.parent) }
                ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive finished the upload and returned no file")
            finished[session.id] = file
            UploadProgress(file.size ?: session.request.size ?: 0L, complete = true)
        }
        else -> throw DriveErrors.toException(answer.status, answer.body, DriveErrors.Surface.UPLOAD_SESSION, answer.retryAfter)
    }

    private suspend fun requireSource(account: AccountId) {
        if (!GoogleOAuth.rolesFor(tokens.grantedScopes(account)).canBeSource) {
            throw CloudException(
                CloudErrorKind.PERMANENT,
                "this Google Drive account has not granted read access (drive.readonly), so it cannot be a transfer source",
                code = "source_scope_not_granted",
            )
        }
    }

    private fun sessionUri(session: UploadSession): String = session.providerMetadata
        ?: throw CloudException(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "the upload session has no URI to resume")

    private class DriveDownload(private val response: Response, override val range: LongRange?) : CloudDownload {
        private val stream: InputStream = response.body?.byteStream()
            ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive returned no body")
        override val contentLength: Long? = response.body?.contentLength()?.takeIf { it >= 0 }

        // Drive puts no revision on a media response; §20.6 compares the one
        // resolveMetadata reported just before this opened.
        override val revision: String? = null

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int =
            stream.read(destination, offset, length)

        override fun close() = response.close()
    }

    internal companion object {
        private const val OCTET = "application/octet-stream"
        private const val ABOUT_USER = "user(displayName,emailAddress,permissionId)"

        /** Google documents a week; a day's margin means §22.5 gives up first. */
        val SESSION_LIFETIME: java.time.Duration = java.time.Duration.ofDays(6)

        /**
         * `Range: bytes=0-262143` means 262144 bytes held. No header means
         * none — Google's documented answer before the first byte arrives,
         * and the captured `upload_status_nothing_received_308`.
         */
        fun acknowledgedOf(range: String?): Long {
            if (range == null) return 0L
            val last = range.substringAfter("bytes=", "").substringAfter('-', "").trim().toLongOrNull()
                ?: throw CloudException(CloudErrorKind.PERMANENT, "Google Drive sent a Range header CloudLug cannot read", code = "bad_range")
            return last + 1
        }

        /** The `upload_id` parameter names a session; the whole URI is kept in providerMetadata. */
        fun uploadIdOf(uri: String): String =
            Regex("[?&]upload_id=([^&]+)").find(uri)?.groupValues?.get(1) ?: uri
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
