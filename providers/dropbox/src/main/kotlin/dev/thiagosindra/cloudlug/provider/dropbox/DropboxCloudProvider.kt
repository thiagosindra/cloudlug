package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ProviderType
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
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxObjects.boolField
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxObjects.longField
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxObjects.stringField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.time.Instant

/**
 * Dropbox as a [CloudProvider] (spec §5, §33 v0.3).
 *
 * Everything Dropbox-specific stops here. The engine sees only the contract and
 * [ProviderCapabilities] (§32.7), which is what lets the same engine drive a
 * provider whose names are case-sensitive and one whose names are not.
 */
class DropboxCloudProvider(
    private val tokens: DropboxTokenSource,
    client: OkHttpClient = OkHttpClient(),
    override val capabilities: ProviderCapabilities = DropboxCapabilities.Default,
) : CloudProvider {

    override val type: ProviderType = ProviderType.DROPBOX

    private val api = DropboxApi(tokens, client)

    /**
     * Bytes Dropbox has acknowledged per open session.
     *
     * `upload_session/finish` needs the total offset, and [UploadSession] is an
     * immutable handle that cannot carry it. This process therefore remembers —
     * and when the process dies, [queryUpload] asks Dropbox instead, which is
     * exactly what §22.5 exists for. Using `request.size` here would be wrong
     * twice over: an object of unknown size (§11) has none, and a resumed
     * upload has not sent all of it.
     */
    private val acknowledged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override suspend fun authenticate(): CloudAccount {
        val account = api.rpcWithoutArgument("/2/users/get_current_account")
        return CloudAccount(
            id = AccountId(account.stringField("account_id") ?: error("no account_id")),
            provider = type,
            displayName = account["name"]?.jsonObject?.stringField("display_name"),
            displayEmail = account.stringField("email"),
            // §7: what was granted, which is not necessarily what was asked
            // for. Dropbox has no endpoint for it, so it comes from the grant.
            grantedScopes = tokens.grantedScopes(),
        )
    }

    /** §8.3. Revoking invalidates the refresh token too, so the grant is really gone. */
    override suspend fun disconnect(account: AccountId) {
        api.rpcWithoutArgument("/2/auth/token/revoke")
    }

    override fun rootOf(account: AccountId): CloudObjectId = DropboxObjects.idOf(DropboxObjects.ROOT)

    override suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject {
        val entry = api.rpc(
            "/2/files/get_metadata",
            buildJsonObject { put("path", DropboxObjects.apiPath(objectId)) },
        )
        return DropboxObjects.toCloudObject(entry, parent = null)
            ?: throw CloudException(CloudErrorKind.NOT_FOUND, "no object ${objectId.opaqueId}")
    }

    override fun listChildren(account: AccountId, parent: CloudObjectId): Flow<CloudObject> =
        listFolder(parent, recursive = false)

    /**
     * §11's depth-first walk, resumable after process death.
     *
     * Dropbox's own cursor cannot be used: ADR-0015 made the resume token the
     * last emitted object's id, and a Dropbox cursor is opaque state that
     * expires. So a resumed walk re-lists and skips until it passes
     * [resumeAfter] — slower, but idempotent, which §11 says is the property
     * that matters because the manifest deduplicates by source object id.
     */
    override fun enumerate(
        account: AccountId,
        selection: CloudSelection,
        resumeAfter: CloudObjectId?,
    ): Flow<CloudObject> = flow {
        // A resume point can be gone by the time a transfer resumes — deleted
        // at the source, or moved out of the selection. Skipping until an id
        // that will never arrive would emit nothing and produce a manifest that
        // looks complete with no work in it. One metadata call decides it, and
        // a full re-walk is safe because §11 deduplicates by source object id.
        var skipping = resumeAfter != null && exists(account, resumeAfter)
        for (root in selection.roots) {
            listFolder(root.cloudObject.id, recursive = true).collect { entry ->
                if (skipping) {
                    if (entry.id == resumeAfter) skipping = false
                } else {
                    emit(entry)
                }
            }
        }
    }

    private suspend fun exists(account: AccountId, objectId: CloudObjectId): Boolean =
        runCatching { resolveMetadata(account, objectId) }.isSuccess

    /**
     * One `list_folder`, followed as many `list_folder/continue` pages as
     * Dropbox offers.
     *
     * Emitted per page rather than collected, so a folder with thousands of
     * children starts drawing rows immediately (§5).
     */
    private fun listFolder(parent: CloudObjectId, recursive: Boolean): Flow<CloudObject> = flow {
        var page = api.rpc(
            "/2/files/list_folder",
            buildJsonObject {
                put("path", DropboxObjects.apiPath(parent))
                put("recursive", recursive)
            },
        )
        while (true) {
            (page["entries"] as? JsonArray).orEmpty().forEach { element ->
                DropboxObjects.toCloudObject(element.jsonObject, parent.takeIf { !recursive })
                    ?.let { emit(it) }
            }
            if (!page.boolField("has_more")) return@flow
            val cursor = page.stringField("cursor") ?: return@flow
            page = api.rpc("/2/files/list_folder/continue", buildJsonObject { put("cursor", cursor) })
        }
    }

    override suspend fun quota(account: AccountId): StorageQuota? {
        val usage = api.rpcWithoutArgument("/2/users/get_space_usage")
        val used = usage.longField("used")
        // An individual account has an "individual" allocation; a team member's
        // allocation is shaped differently, and §20.7 would rather report no
        // quota than a wrong one.
        val allocation = usage["allocation"]?.jsonObject
        val total = allocation?.longField("allocated")
        if (used == null || total == null) return null
        return StorageQuota(totalBytes = total, usedBytes = used, availableBytes = total - used)
    }

    override suspend fun openDownload(
        account: AccountId,
        objectId: CloudObjectId,
        range: LongRange?,
    ): CloudDownload {
        val effective = range?.takeIf { capabilities.supportsRangeDownload }
        val response = api.content(
            "/2/files/download",
            buildJsonObject { put("path", DropboxObjects.apiPath(objectId)) },
            range = effective,
        )
        val metadata = api.resultOf(response)
        return DropboxDownload(response, metadata.stringField("rev"), effective)
    }

    /**
     * §10's enclosing folder, and any missing ancestor of it.
     *
     * `create_folder_v2` is not idempotent — it fails with `conflict/folder`
     * when the folder already exists — so an existing folder is looked up
     * rather than treated as an error. That matters on resume: the enclosing
     * folder is created once, and a resumed transfer must not fail because its
     * own folder is already there.
     */
    override suspend fun prepareDestination(
        account: AccountId,
        parent: CloudObjectId,
        relativePath: CloudPath,
    ): DestinationObject {
        var currentPath = DropboxObjects.apiPath(parent)
        var current = parent
        var created = false

        for (segment in relativePath.segments) {
            currentPath = "$currentPath/$segment"
            val existing = runCatching { resolveByPath(currentPath) }.getOrNull()
            if (existing != null) {
                current = existing.id
                continue
            }
            val result = api.rpc(
                "/2/files/create_folder_v2",
                buildJsonObject { put("path", currentPath) },
            )
            val folder = result["metadata"]?.jsonObject
                ?: throw CloudException(CloudErrorKind.PERMANENT, "create_folder_v2 returned no metadata")
            current = DropboxObjects.idOf(folder.stringField("id") ?: currentPath)
            created = true
        }
        return DestinationObject(id = current, relativePath = relativePath, created = created)
    }

    /**
     * §19.3 asks what is already called this.
     *
     * Dropbox allows no duplicate siblings and folds case, so there is at most
     * one answer — but the contract returns a list because other providers
     * return several, and the collision algorithm is written once for all of
     * them.
     */
    override suspend fun lookupDestination(
        account: AccountId,
        parent: CloudObjectId,
        name: String,
    ): List<CloudObject> {
        val path = "${DropboxObjects.apiPath(parent)}/$name"
        return listOfNotNull(runCatching { resolveByPath(path) }.getOrNull())
    }

    private suspend fun resolveByPath(path: String): CloudObject? =
        DropboxObjects.toCloudObject(
            api.rpc("/2/files/get_metadata", buildJsonObject { put("path", path) }),
            parent = null,
        )

    override suspend fun beginUpload(account: AccountId, request: UploadRequest): UploadSession {
        val started = api.content(
            "/2/files/upload_session/start",
            buildJsonObject { put("close", false) },
            payload = ByteArray(0).toRequestBody(null),
        )
        val sessionId = started.use { it.body?.string().orEmpty() }
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
            .stringField("session_id")
            ?: throw CloudException(CloudErrorKind.PERMANENT, "upload_session/start returned no session_id")

        return UploadSession(id = sessionId, request = request)
    }

    override suspend fun uploadChunk(session: UploadSession, chunk: Chunk): UploadProgress {
        require(chunk.length.toLong() % capabilities.uploadChunkAlignment == 0L || chunk.isFinal) {
            "a non-final chunk of ${chunk.length} bytes is not aligned to ${capabilities.uploadChunkAlignment}"
        }
        api.content(
            "/2/files/upload_session/append_v2",
            buildJsonObject {
                put("cursor", cursorFor(session, chunk.offset))
                put("close", false)
            },
            payload = chunk.bytes.toRequestBody(null, 0, chunk.length),
        ).close()

        acknowledged[session.id] = chunk.endExclusive
        return UploadProgress(chunk.endExclusive, complete = chunk.isFinal)
    }

    /**
     * §22.5 asks Dropbox where the session really got to.
     *
     * There is no route that answers directly. Appending zero bytes at an
     * offset we believe is current either succeeds — confirming it — or fails
     * with `incorrect_offset`, which carries the true offset. Both outcomes are
     * answers, so the error body is read rather than thrown away.
     *
     * This is the path a transfer takes after process death, when nothing in
     * memory knows how far the upload got.
     */
    override suspend fun queryUpload(session: UploadSession): UploadProgress {
        val believed = acknowledged[session.id] ?: session.providerMetadata?.toLongOrNull() ?: 0L
        val (status, body) = api.contentAllowingFailure(
            "/2/files/upload_session/append_v2",
            buildJsonObject {
                put("cursor", cursorFor(session, believed))
                put("close", false)
            },
            payload = ByteArray(0).toRequestBody(null),
        )

        if (status in 200..299) {
            acknowledged[session.id] = believed
            return UploadProgress(believed)
        }

        val actual = DropboxErrors.correctOffsetOf(body)
        if (actual == null) throw api.failureFor(status, body)

        acknowledged[session.id] = actual
        return UploadProgress(actual)
    }

    override suspend fun finishUpload(session: UploadSession): CloudObject {
        val request = session.request
        val response = api.content(
            "/2/files/upload_session/finish",
            buildJsonObject {
                put("cursor", cursorFor(session, acknowledged[session.id] ?: queryUpload(session).acknowledgedBytes))
                put(
                    "commit",
                    buildJsonObject {
                        put("path", "${DropboxObjects.apiPath(request.parent)}/${request.name}")
                        put("mode", "add")
                        put("autorename", false)
                        put("mute", true)
                        request.modifiedAt
                            ?.takeIf { capabilities.supportsModifiedTimeWrite }
                            ?.let { put("client_modified", isoSeconds(it)) }
                    },
                )
            },
            payload = ByteArray(0).toRequestBody(null),
        )
        val entry = response.use { kotlinx.serialization.json.Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject }
        acknowledged.remove(session.id)
        return DropboxObjects.toCloudObject(entry, parent = request.parent)
            ?: throw CloudException(CloudErrorKind.PERMANENT, "upload_session/finish returned no file metadata")
    }

    /**
     * §22.2 and §22.3. Dropbox has no explicit abort: a session that is never
     * finished expires on its own, and closing it is the nearest thing to
     * telling the service we are done.
     */
    override suspend fun abortUpload(session: UploadSession) {
        val believed = acknowledged.remove(session.id) ?: session.providerMetadata?.toLongOrNull() ?: 0L
        runCatching {
            api.content(
                "/2/files/upload_session/append_v2",
                buildJsonObject {
                    put("cursor", cursorFor(session, believed))
                    put("close", true)
                },
                payload = ByteArray(0).toRequestBody(null),
            ).close()
        }
    }

    private fun cursorFor(session: UploadSession, offset: Long): JsonObject = buildJsonObject {
        put("session_id", session.id)
        put("offset", offset)
    }

    /** Dropbox rejects sub-second precision on `client_modified`. */
    private fun isoSeconds(instant: Instant): String =
        instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** Streams the response body, so a large object never lands in memory whole. */
    private class DropboxDownload(
        private val response: Response,
        override val revision: String?,
        override val range: LongRange?,
    ) : CloudDownload {

        private val stream: InputStream = response.body?.byteStream()
            ?: throw CloudException(CloudErrorKind.PERMANENT, "Dropbox returned no body")

        override val contentLength: Long? = response.body?.contentLength()?.takeIf { it >= 0 }

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int =
            stream.read(destination, offset, length)

        override fun close() = response.close()
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
