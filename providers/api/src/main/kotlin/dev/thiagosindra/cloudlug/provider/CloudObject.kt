package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import java.time.Instant

/**
 * Stable, provider-scoped identity for an object (spec §6).
 *
 * Paths are presentation/layout information; this is identity. Providers that
 * have no stable ID (declared through
 * [ProviderCapabilities.supportsStableObjectIds]) may put a path in [opaqueId],
 * but the engine still treats it as opaque and never parses it.
 */
data class CloudObjectId(
    val provider: ProviderType,
    val opaqueId: String,
) {
    init {
        require(opaqueId.isNotBlank()) { "opaqueId must not be blank" }
    }
}

/**
 * A file, folder, shortcut or provider-native document as the adapter sees it
 * (spec §6).
 */
data class CloudObject(
    val id: CloudObjectId,
    val name: String,
    val type: CloudObjectType,
    val parentId: CloudObjectId?,
    /** Null for provider-native documents, which have no byte stream (spec §20.1). */
    val size: Long?,
    val modifiedAt: Instant?,
    val providerHash: ProviderHash?,
    /** Opaque revision/version marker used to detect source changes (spec §20.6). */
    val revision: String?,
    val mimeType: String?,
    /** Export MIME types, for [CloudObjectType.PROVIDER_NATIVE_DOCUMENT] only (spec §20.1). */
    val exportFormats: List<String>? = null,
) {
    val isFolder: Boolean get() = type == CloudObjectType.FOLDER

    /** True when the object carries bytes CloudLug can stream as-is. */
    val hasByteStream: Boolean get() = type == CloudObjectType.FILE
}
