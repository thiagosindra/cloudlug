package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * How a Drive object is named, and how a `File` resource becomes a [CloudObject].
 *
 * Everything is addressed by file id. The root is the literal `root`, which the
 * API accepts as an alias for My Drive (§20.8: My Drive only) — ADR-0026's
 * "spelled differently everywhere".
 */
internal object DriveObjects {

    const val ROOT = "root"
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
    private const val NATIVE_PREFIX = "application/vnd.google-apps."

    /** What every request that returns a file asks for, and nothing more. */
    const val FILE_FIELDS = "id,name,mimeType,parents,size,md5Checksum,sha256Checksum,modifiedTime,version,trashed"

    fun idOf(raw: String): CloudObjectId = CloudObjectId(ProviderType.GOOGLE_DRIVE, raw)

    /**
     * A `File` resource. Null for a trashed one: a listing filters them, but a
     * `files.get` can still return one, and it is not an object to transfer to
     * or compare against.
     *
     * [parent] wins over the resource's own `parents` when given, because the
     * caller asked about one folder and Drive can report several parents for
     * an object outside My Drive.
     */
    fun toCloudObject(file: JsonObject, parent: CloudObjectId?): CloudObject? {
        if (file.text("trashed") == "true") return null
        val id = file.text("id") ?: return null
        val mime = file.text("mimeType")
        val type = when {
            mime == FOLDER_MIME -> CloudObjectType.FOLDER
            // §20.2: shortcuts are not followed.
            mime == SHORTCUT_MIME -> CloudObjectType.SHORTCUT
            // §20.1: Docs, Sheets and the rest have no bytes and no size.
            mime != null && mime.startsWith(NATIVE_PREFIX) -> CloudObjectType.PROVIDER_NATIVE_DOCUMENT
            else -> CloudObjectType.FILE
        }
        val md5 = file.text("md5Checksum")?.lowercase()?.let { ProviderHash(HashAlgorithm.MD5, it) }
        val sha256 = file.text("sha256Checksum")?.lowercase()?.let { ProviderHash(HashAlgorithm.SHA256, it) }
        val reportedParent = (file["parents"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }

        return CloudObject(
            id = idOf(id),
            name = file.text("name").orEmpty(),
            type = type,
            parentId = parent ?: reportedParent?.let(::idOf),
            // Drive sends size as a string. Only files carry one: §11 tells a
            // folder's absent size apart from a file's unknown one.
            size = if (type == CloudObjectType.FILE) file.text("size")?.toLongOrNull() else null,
            modifiedAt = file.text("modifiedTime")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            providerHash = md5,
            // §19.4: Drive also reports SHA-256; §21 compares it when present.
            additionalHashes = listOfNotNull(sha256),
            // `version` increases on every change to the file, which is what
            // §20.6 asks a revision to detect.
            revision = file.text("version"),
            mimeType = mime,
        )
    }

    /** A string field, or a boolean rendered as one; null when absent. */
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
