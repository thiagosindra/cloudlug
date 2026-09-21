package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * How a Dropbox object is named, and how its metadata becomes a [CloudObject].
 *
 * ### The root is the empty string, and [CloudObjectId] forbids that
 *
 * Dropbox addresses the account root as `""`. `CloudObjectId` requires a
 * non-blank id, for the good reason that a blank one is almost always a bug.
 * ADR-0026 predicted this exact collision when it said root "is spelled
 * differently everywhere", so the adapter carries [ROOT] as its own spelling and
 * translates at the boundary. Nothing above this file ever sees `""`.
 *
 * ### Ids, not paths
 *
 * Every other object is addressed by its Dropbox id (`id:AbC123`), which
 * survives a move or a rename. §11 needs that to resume an enumeration after
 * process death: a path-addressed resume would silently skip or repeat objects
 * if the user reorganised their Dropbox mid-transfer.
 */
internal object DropboxObjects {

    /** The adapter's spelling of the account root; sent to Dropbox as `""`. */
    const val ROOT = "/"

    /** What Dropbox is given for [id]: the root becomes the empty string. */
    fun apiPath(id: CloudObjectId): String = if (id.opaqueId == ROOT) "" else id.opaqueId

    fun idOf(raw: String): CloudObjectId =
        CloudObjectId(ProviderType.DROPBOX, raw.ifBlank { ROOT })

    /**
     * A `FileMetadata`, `FolderMetadata` or `DeletedMetadata` entry.
     *
     * Dropbox has no shortcuts and no native documents — both of §20.1 and
     * §20.2's awkward cases are Drive's — so every entry is a file or a folder.
     * A deleted entry returns null: `list_folder` with `recursive` can include
     * tombstones, and they are not objects to transfer.
     */
    fun toCloudObject(entry: JsonObject, parent: CloudObjectId?): CloudObject? {
        val tag = entry[".tag"]?.stringOrNull()
        val type = when (tag) {
            "file" -> CloudObjectType.FILE
            "folder" -> CloudObjectType.FOLDER
            else -> return null
        }

        val id = entry["id"]?.stringOrNull() ?: entry["path_lower"]?.stringOrNull() ?: return null
        val contentHash = entry["content_hash"]?.stringOrNull()

        return CloudObject(
            id = idOf(id),
            name = entry["name"]?.stringOrNull().orEmpty(),
            type = type,
            parentId = parent,
            // A folder has no size, and §11 distinguishes that from an unknown
            // size, so only files carry one.
            size = if (type == CloudObjectType.FILE) entry.longField("size") else null,
            modifiedAt = entry["server_modified"]?.stringOrNull()?.let(::parseInstant),
            providerHash = contentHash?.let { ProviderHash(HashAlgorithm.DROPBOX_CONTENT_HASH, it) },
            // §20.6 detects a source that changed mid-transfer. Dropbox's rev
            // changes on every write, which is exactly the marker wanted.
            revision = entry["rev"]?.stringOrNull(),
            mimeType = null,
        )
    }

    /** Dropbox timestamps are ISO-8601 in UTC; an unparseable one is absent, not fatal. */
    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    fun JsonObject.stringField(name: String): String? = this[name]?.stringOrNull()

    fun JsonObject.boolField(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    fun JsonObject.longField(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
