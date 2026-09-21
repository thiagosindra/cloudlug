package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderType
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
import dev.thiagosindra.cloudlug.hashing.Hashers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.time.Duration

/**
 * Thrown when [FakeCloudProvider.interruptProcess] simulates the app being
 * killed (spec §31.4). Deliberately not a [CloudException]: the process dying
 * is not something the retry policy should reason about, and the engine must
 * recover from it through the database, not through a catch block.
 */
class ProcessInterruptedException(message: String) : RuntimeException(message)

/**
 * An in-memory [CloudProvider] with programmable failures (spec §31.3).
 *
 * Built before any real adapter, on purpose: it is what lets the engine's
 * recovery, retry, collision and verification behaviour be tested without a
 * network, an account or a provider's rate limits. It is also the reference for
 * what a well-behaved adapter does — every rule the contract suite checks
 * (`ProviderContractTest`) holds here.
 *
 * Capabilities are constructor parameters so one class can stand in for
 * providers with quite different behaviour: case-insensitive names, duplicate
 * siblings, no resumable upload, no server hash, and so on.
 */
class FakeCloudProvider(
    override val type: ProviderType = ProviderType.FAKE,
    override val capabilities: ProviderCapabilities = defaultCapabilities(),
    val storage: FakeCloudStorage = FakeCloudStorage(type, capabilities.nativeHashAlgorithm),
    private val accountId: AccountId = AccountId("fake-account"),
    private val totalQuotaBytes: Long? = null,
    private val enumerationPageSize: Int = 100,
) : CloudProvider {

    /**
     * How long one source read takes (spec §31.3).
     *
     * Required, not a convenience. With no delay a whole file moves inside a
     * single scheduler tick, so every pause, resume and cancel lands on a state
     * boundary — precisely the case §22 does not need help with. Mid-file
     * behaviour cannot be tested at all without a way to stop the clock partway
     * through an object.
     *
     * Settable rather than a constructor parameter so a test can slow one side
     * only, or start slow and speed up once it has the position it wants.
     */
    var readDelay: Duration = Duration.ZERO

    /** How long one destination chunk upload takes (spec §31.3). */
    var uploadChunkDelay: Duration = Duration.ZERO

    private val injections = mutableListOf<FailureInjection>()
    private val sessions = linkedMapOf<String, PendingUpload>()
    private var nextSessionId = 1
    private var interrupted = false

    /** Calls recorded for assertions, e.g. that verification did not re-download. */
    val calls = mutableListOf<String>()

    fun inject(injection: FailureInjection) {
        injections += injection
    }

    fun clearInjections() {
        injections.clear()
        interrupted = false
    }

    /**
     * Simulates the *process* dying rather than a network error: calls fail
     * with [ProcessInterruptedException], which is not a [CloudException] and
     * so is not retried or turned into an item failure. That is what makes the
     * §31.4 crash-recovery scenarios distinguishable from §23 retry scenarios.
     */
    fun interruptProcess() {
        interrupted = true
    }

    override suspend fun authenticate(): CloudAccount {
        record(FailureInjection.Operation.AUTHENTICATE)
        return CloudAccount(
            id = accountId,
            provider = type,
            displayName = "Fake Account",
            displayEmail = "fake@example.test",
            grantedScopes = setOf("read", "write"),
        )
    }

    override suspend fun disconnect(account: AccountId) {
        calls += "disconnect"
        sessions.clear()
    }

    override suspend fun resolveMetadata(account: AccountId, objectId: CloudObjectId): CloudObject {
        record(FailureInjection.Operation.RESOLVE_METADATA)
        return storage.find(objectId.opaqueId)
            ?: throw CloudException(CloudErrorKind.NOT_FOUND, "no object ${objectId.opaqueId}", code = "404")
    }

    /**
     * Depth-first, parents before children, paged (spec §5, §11). Emits empty
     * folders too, because the destination tree preserves them (§20.5).
     */
    override fun enumerate(
        account: AccountId,
        selection: CloudSelection,
        resumeAfter: CloudObjectId?,
    ): Flow<CloudObject> {
        return flow {
            // Recorded inside the flow: enumerate no longer suspends, so nothing
            // happens until collection starts (spec §5).
            record(FailureInjection.Operation.ENUMERATE)
            var emitted = 0
            // A resume point that is no longer in the account can never be
            // reached, and skipping until it arrives would emit nothing at all
            // — a manifest that looks complete with no work in it. §11 permits
            // a restart because the manifest deduplicates by source object id,
            // so falling back to a full walk is both safe and the only correct
            // option here.
            var skipping = resumeAfter != null && storage.find(resumeAfter.opaqueId) != null
            val stack = ArrayDeque(selection.objects.reversed())
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (skipping) {
                    if (current.id == resumeAfter) skipping = false
                } else {
                    emit(current)
                    emitted++
                    if (emitted % enumerationPageSize == 0) {
                        // A page boundary: where a real adapter would fetch the
                        // next page and where an interrupted walk resumes.
                        failIfInjected(FailureInjection.Operation.ENUMERATE)
                    }
                }
                if (current.type == CloudObjectType.FOLDER) {
                    storage.childrenOf(current.id.opaqueId).reversed().forEach(stack::addLast)
                }
            }
        }
    }

    /** One level, in insertion order (spec §9). */
    override fun rootOf(account: AccountId): CloudObjectId =
        CloudObjectId(type, FakeCloudStorage.ROOT_ID)

    override fun listChildren(account: AccountId, parent: CloudObjectId): Flow<CloudObject> = flow {
        record(FailureInjection.Operation.ENUMERATE)
        failIfInjected(FailureInjection.Operation.ENUMERATE)
        storage.childrenOf(parent.opaqueId).forEach { emit(it) }
    }

    override suspend fun quota(account: AccountId): StorageQuota? {
        record(FailureInjection.Operation.QUOTA)
        val total = totalQuotaBytes ?: return null
        return StorageQuota(
            totalBytes = total,
            usedBytes = storage.usedBytes,
            availableBytes = total - storage.usedBytes,
        )
    }

    override suspend fun openDownload(
        account: AccountId,
        objectId: CloudObjectId,
        range: LongRange?,
    ): CloudDownload {
        record(FailureInjection.Operation.OPEN_DOWNLOAD)
        val obj = storage.find(objectId.opaqueId)
            ?: throw CloudException(CloudErrorKind.NOT_FOUND, "no object ${objectId.opaqueId}", code = "404")
        if (obj.type != CloudObjectType.FILE) {
            throw CloudException(CloudErrorKind.UNSUPPORTED, "${obj.name} has no byte stream", code = "no_bytes")
        }
        val content = storage.contentOf(objectId.opaqueId) ?: ByteArray(0)
        val effective = when {
            range == null || !capabilities.supportsRangeDownload -> content
            else -> content.copyOfRange(
                range.first.toInt().coerceIn(0, content.size),
                (range.last + 1).toInt().coerceIn(0, content.size),
            )
        }
        return FakeDownload(effective, obj.revision, range)
    }

    override suspend fun prepareDestination(
        account: AccountId,
        parent: CloudObjectId,
        relativePath: CloudPath,
    ): DestinationObject {
        record(FailureInjection.Operation.PREPARE_DESTINATION)
        var currentId = parent.opaqueId
        var created = false
        relativePath.segments.forEach { segment ->
            val (folder, wasCreated) = storage.ensureFolder(segment, currentId)
            currentId = folder.id.opaqueId
            created = created || wasCreated
        }
        return DestinationObject(CloudObjectId(type, currentId), relativePath, created)
    }

    override suspend fun lookupDestination(
        account: AccountId,
        parent: CloudObjectId,
        name: String,
    ): List<CloudObject> {
        record(FailureInjection.Operation.LOOKUP_DESTINATION)
        return storage.childrenOf(parent.opaqueId).filter {
            if (capabilities.caseSensitiveNames) it.name == name else it.name.equals(name, ignoreCase = true)
        }
    }

    override suspend fun beginUpload(account: AccountId, request: UploadRequest): UploadSession {
        record(FailureInjection.Operation.BEGIN_UPLOAD)
        val id = "session-${nextSessionId++}"
        sessions[id] = PendingUpload(request)
        return UploadSession(
            id = id,
            request = request,
            providerMetadata = """{"upload":"$id"}""",
            expiresAt = Instant.EPOCH.plusSeconds(7 * 24 * 60 * 60),
        )
    }

    override suspend fun uploadChunk(session: UploadSession, chunk: Chunk): UploadProgress {
        if (uploadChunkDelay > Duration.ZERO) delay(uploadChunkDelay)
        record(FailureInjection.Operation.UPLOAD_CHUNK)
        val pending = sessions[session.id]
            ?: throw CloudException(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "unknown session", code = "expired")
        require(chunk.length % capabilities.uploadChunkAlignment == 0L || chunk.isFinal) {
            "chunk of ${chunk.length} bytes violates alignment ${capabilities.uploadChunkAlignment}"
        }
        require(chunk.length <= capabilities.maxUploadChunkBytes) { "chunk exceeds maxUploadChunkBytes" }
        if (chunk.offset != pending.received.toLong()) {
            throw CloudException(
                CloudErrorKind.PERMANENT,
                "chunk at ${chunk.offset} does not continue from ${pending.received}",
                code = "bad_offset",
            )
        }
        val bytes = corruptIfInjected(chunk.bytes.copyOfRange(0, chunk.length))
        pending.buffer.write(bytes)
        pending.received += chunk.length
        return UploadProgress(pending.received.toLong(), complete = chunk.isFinal)
    }

    override suspend fun queryUpload(session: UploadSession): UploadProgress {
        record(FailureInjection.Operation.QUERY_UPLOAD)
        val pending = sessions[session.id]
            ?: throw CloudException(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "unknown session", code = "expired")
        return UploadProgress(pending.received.toLong())
    }

    override suspend fun finishUpload(session: UploadSession): CloudObject {
        record(FailureInjection.Operation.FINISH_UPLOAD)
        val pending = sessions.remove(session.id)
            ?: throw CloudException(CloudErrorKind.UPLOAD_SESSION_EXPIRED, "unknown session", code = "expired")
        val request = session.request
        return storage.store(
            name = request.name,
            parentId = request.parent.opaqueId,
            content = pending.buffer.toByteArray(),
            modifiedAt = request.modifiedAt.takeIf { capabilities.supportsModifiedTimeWrite },
            mimeType = request.mimeType,
        )
    }

    override suspend fun abortUpload(session: UploadSession) {
        record(FailureInjection.Operation.ABORT_UPLOAD)
        sessions.remove(session.id)
    }

    /** True when no resumable upload is still open — nothing was left half-written. */
    fun hasOpenSessions(): Boolean = sessions.isNotEmpty()

    private fun record(operation: FailureInjection.Operation) {
        calls += operation.name
        if (interrupted) throw ProcessInterruptedException("process interrupted before $operation")
        failIfInjected(operation)
    }

    private fun failIfInjected(operation: FailureInjection.Operation) {
        val injection = injections.firstOrNull {
            it.times > 0 &&
                it.fault != FailureInjection.Fault.CORRUPT_CHUNK &&
                it.fault != FailureInjection.Fault.DISCONNECT_AFTER_BYTES &&
                (it.onOperation == operation || it.onOperation == FailureInjection.Operation.ANY)
        } ?: return
        injection.times--
        if (injection.fault == FailureInjection.Fault.PROCESS_INTERRUPTED) {
            interrupted = true
            throw ProcessInterruptedException("process interrupted during $operation")
        }
        throw injection.toException()
    }

    private fun corruptIfInjected(bytes: ByteArray): ByteArray {
        val injection = injections.firstOrNull {
            it.times > 0 && it.fault == FailureInjection.Fault.CORRUPT_CHUNK
        } ?: return bytes
        injection.times--
        if (bytes.isEmpty()) return bytes
        return bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
    }

    private fun disconnectAfter(): Long? = injections.firstOrNull {
        it.times > 0 && it.fault == FailureInjection.Fault.DISCONNECT_AFTER_BYTES
    }?.let { injection ->
        injection.times--
        injection.afterBytes
    }

    private class PendingUpload(val request: UploadRequest) {
        val buffer = java.io.ByteArrayOutputStream()
        var received: Int = 0
    }

    /** A download that can drop mid-stream, the way a real connection does. */
    private inner class FakeDownload(
        private val content: ByteArray,
        override val revision: String?,
        override val range: LongRange?,
    ) : CloudDownload {
        override val contentLength: Long = content.size.toLong()
        private var position = 0
        private val dropAfter: Long? = disconnectAfter()

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            // Before the outcome, not after: the wire time is spent whether the
            // read succeeds or the connection then drops.
            if (readDelay > Duration.ZERO) delay(readDelay)
            failIfInjected(FailureInjection.Operation.READ)
            if (dropAfter != null && position >= dropAfter) {
                throw CloudException(CloudErrorKind.TRANSIENT_NETWORK, "connection reset", code = "reset")
            }
            if (position >= content.size) return -1
            val available = minOf(length, content.size - position)
            val cappedByDrop = dropAfter?.let { minOf(available.toLong(), it - position).toInt() } ?: available
            val take = maxOf(cappedByDrop, 0)
            if (take == 0) {
                throw CloudException(CloudErrorKind.TRANSIENT_NETWORK, "connection reset", code = "reset")
            }
            content.copyInto(destination, offset, position, position + take)
            position += take
            return take
        }

        override fun close() = Unit
    }

    companion object {
        /**
         * A capable, well-behaved provider: stable IDs, ranges, resumable
         * uploads, a server hash, case-sensitive names and no duplicate
         * siblings.
         */
        fun defaultCapabilities(
            canBeSource: Boolean = true,
            canBeDestination: Boolean = true,
            nativeHashAlgorithm: HashAlgorithm? = HashAlgorithm.SHA256,
            supportsServerHash: Boolean = nativeHashAlgorithm != null,
            caseSensitiveNames: Boolean = true,
            allowsDuplicateSiblingNames: Boolean = false,
            supportsRangeDownload: Boolean = true,
            supportsResumableUpload: Boolean = true,
            uploadChunkAlignment: Long = 256L * 1024,
            maxUploadChunkBytes: Long = 64L * 1024 * 1024,
            illegalNameCharacters: Set<Char> = setOf('/', '\\'),
            maxNameLength: Int = 255,
            maxPathLength: Int? = null,
            disallowsTrailingSpaceOrDot: Boolean = false,
        ) = ProviderCapabilities(
            canBeSource = canBeSource,
            canBeDestination = canBeDestination,
            supportsRangeDownload = supportsRangeDownload,
            supportsResumableUpload = supportsResumableUpload,
            supportsServerHash = supportsServerHash,
            nativeHashAlgorithm = nativeHashAlgorithm,
            supportsFolderPicker = true,
            supportsMultipleSourceSelection = true,
            supportsStableObjectIds = true,
            supportsModifiedTimeWrite = true,
            supportsCustomMetadata = false,
            caseSensitiveNames = caseSensitiveNames,
            allowsDuplicateSiblingNames = allowsDuplicateSiblingNames,
            uploadChunkAlignment = uploadChunkAlignment,
            maxUploadChunkBytes = maxUploadChunkBytes,
            illegalNameCharacters = illegalNameCharacters,
            maxNameLength = maxNameLength,
            maxPathLength = maxPathLength,
            disallowsTrailingSpaceOrDot = disallowsTrailingSpaceOrDot,
        )

        /** Hash helper for tests that assert on stored content. */
        fun hash(bytes: ByteArray, algorithm: HashAlgorithm) =
            Hashers.create(algorithm).also { it.update(bytes) }.digest()
    }
}
