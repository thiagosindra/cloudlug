package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.hashing.Hashers
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.model.ProviderType
import java.time.Instant

/**
 * The in-memory tree a [FakeCloudProvider] serves.
 *
 * Kept separate from the provider so a test can build a source tree, hand it to
 * a provider, and then assert on what the destination tree looks like
 * afterwards.
 */
class FakeCloudStorage(
    private val providerType: ProviderType = ProviderType.FAKE,
    private val hashAlgorithm: HashAlgorithm? = HashAlgorithm.SHA256,
) {
    private val objects = linkedMapOf<String, FakeObject>()
    private var nextId = 1

    /** Total bytes stored, for quota simulation. */
    val usedBytes: Long get() = objects.values.sumOf { it.content?.size?.toLong() ?: 0L }

    fun folder(name: String, parent: CloudObjectId? = null): CloudObjectId =
        put(FakeObject(id = newId(), name = name, parentId = parent?.opaqueId, type = CloudObjectType.FOLDER))

    fun file(
        name: String,
        content: ByteArray,
        parent: CloudObjectId? = null,
        revision: String = "rev-1",
        mimeType: String? = "application/octet-stream",
    ): CloudObjectId = put(
        FakeObject(
            id = newId(),
            name = name,
            parentId = parent?.opaqueId,
            type = CloudObjectType.FILE,
            content = content,
            revision = revision,
            mimeType = mimeType,
        ),
    )

    /** A provider-native document: no bytes, no size (spec §20.1). */
    fun nativeDocument(
        name: String,
        parent: CloudObjectId? = null,
        exportFormats: List<String> = listOf("application/pdf"),
    ): CloudObjectId = put(
        FakeObject(
            id = newId(),
            name = name,
            parentId = parent?.opaqueId,
            type = CloudObjectType.PROVIDER_NATIVE_DOCUMENT,
            exportFormats = exportFormats,
        ),
    )

    /** A shortcut, which is never followed (spec §20.2). */
    fun shortcut(name: String, parent: CloudObjectId? = null): CloudObjectId =
        put(FakeObject(id = newId(), name = name, parentId = parent?.opaqueId, type = CloudObjectType.SHORTCUT))

    fun childrenOf(parentId: String?): List<CloudObject> =
        objects.values.filter { it.parentId == canonical(parentId) }.map { it.toCloudObject() }

    fun find(id: String): CloudObject? = objects[id]?.toCloudObject()

    companion object {
        /** What [FakeCloudProvider.rootOf] hands back; see ADR-0026. */
        const val ROOT_ID = "root"
    }

    fun contentOf(id: String): ByteArray? = objects[id]?.content

    fun rename(id: String, name: String) {
        objects[id] = objects.getValue(id).copy(name = name)
    }

    /** Replaces an object's bytes and bumps its revision, simulating an edit at the source. */
    fun mutate(id: String, content: ByteArray, revision: String) {
        objects[id] = objects.getValue(id).copy(content = content, revision = revision)
    }

    internal fun store(
        name: String,
        parentId: String?,
        content: ByteArray,
        modifiedAt: Instant?,
        mimeType: String?,
    ): CloudObject {
        val id = put(
            FakeObject(
                id = newId(),
                name = name,
                parentId = parentId,
                type = CloudObjectType.FILE,
                content = content,
                modifiedAt = modifiedAt,
                mimeType = mimeType,
            ),
        )
        return objects.getValue(id.opaqueId).toCloudObject()
    }

    internal fun ensureFolder(name: String, parentId: String?): Pair<CloudObject, Boolean> {
        val existing = objects.values.firstOrNull {
            it.parentId == canonical(parentId) && it.name == name && it.type == CloudObjectType.FOLDER
        }
        if (existing != null) return existing.toCloudObject() to false
        val id = folder(name, parentId?.let { CloudObjectId(providerType, it) })
        return objects.getValue(id.opaqueId).toCloudObject() to true
    }

    private fun put(obj: FakeObject): CloudObjectId {
        objects[obj.id] = obj.copy(parentId = canonical(obj.parentId))
        return CloudObjectId(providerType, obj.id)
    }

    /**
     * The root is spelled two ways and stored one way.
     *
     * A caller that went through [FakeCloudProvider.rootOf] holds [ROOT_ID],
     * while the demo tree and most tests seed with no parent at all. Both mean
     * the top of the account, so every parent is folded to `null` on the way in
     * and on every lookup. Without this the two spellings silently describe
     * different places, which is the defect ADR-0026 records.
     */
    private fun canonical(parentId: String?): String? = parentId?.takeUnless { it == ROOT_ID }

    private fun newId(): String = "obj-${nextId++}"

    private fun FakeObject.toCloudObject() = CloudObject(
        id = CloudObjectId(providerType, id),
        name = name,
        type = type,
        parentId = parentId?.let { CloudObjectId(providerType, it) },
        size = content?.size?.toLong(),
        modifiedAt = modifiedAt,
        providerHash = content?.let { bytes ->
            hashAlgorithm?.let { algorithm ->
                Hashers.create(algorithm).also { it.update(bytes) }.digest()
            }
        },
        revision = revision,
        mimeType = mimeType,
        exportFormats = exportFormats,
    )

    private data class FakeObject(
        val id: String,
        val name: String,
        val parentId: String?,
        val type: CloudObjectType,
        val content: ByteArray? = null,
        val modifiedAt: Instant? = null,
        val revision: String? = null,
        val mimeType: String? = null,
        val exportFormats: List<String>? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is FakeObject && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    /** Hash of stored content, for assertions. */
    fun hashOf(id: String, algorithm: HashAlgorithm): ProviderHash? = contentOf(id)?.let { bytes ->
        Hashers.create(algorithm).also { it.update(bytes) }.digest()
    }
}
