package dev.thiagosindra.cloudlug.model

/**
 * A cloud storage service CloudLug can talk to.
 *
 * The transfer engine must never branch on this value: behaviour is driven by
 * `ProviderCapabilities` instead (spec §5, §32.7). It exists so that object
 * identity is unambiguous across providers (spec §6) and so the UI can name the
 * two sides of a transfer.
 */
enum class ProviderType {
    DROPBOX,
    GOOGLE_DRIVE,

    /** Test-only provider used by the contract suite and the app demo (spec §31.3). */
    FAKE,
}

/** Identifies a connected account (spec §7). Multiple accounts per provider are supported. */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies a transfer (spec §12.1). */
@JvmInline
value class TransferId(val value: String) {
    init {
        require(value.isNotBlank()) { "TransferId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies one manifest row: a file, folder or unsupported object (spec §12.2). */
@JvmInline
value class TransferItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "TransferItemId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies one cached byte range on local storage (spec §12.3). */
@JvmInline
value class CacheChunkId(val value: String) {
    init {
        require(value.isNotBlank()) { "CacheChunkId must not be blank" }
    }

    override fun toString(): String = value
}
