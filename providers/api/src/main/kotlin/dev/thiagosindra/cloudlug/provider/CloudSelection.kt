package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId

/**
 * What the user picked on one side of a transfer (spec §9).
 *
 * A selection may be a single file, several files, one or more directories, or
 * a mixture, subject to
 * [ProviderCapabilities.supportsMultipleSourceSelection]. The transfer engine
 * must not care how the selection was obtained — in-app browser, provider
 * picker or a future mechanism.
 */
data class CloudSelection(
    val accountId: AccountId,
    val roots: List<CloudObject>,
    /**
     * Opaque provider cursor for resuming an interrupted enumeration (spec §11,
     * §12.1). Null starts from the beginning. An adapter that cannot resume from
     * a cursor may restart enumeration; the manifest builder deduplicates by
     * source object ID, so restarting is correct, only slower.
     */
    val resumeCursor: String? = null,
) {
    init {
        require(roots.isNotEmpty()) { "A selection must contain at least one object" }
        require(roots.all { it.id.provider == roots.first().id.provider }) {
            "A selection must not mix providers"
        }
    }

    val provider get() = roots.first().id.provider
}

/**
 * Obtains selections from the user (spec §9). Implemented by the UI layer; the
 * engine depends on the resulting [CloudSelection] only.
 */
interface CloudSelectionProvider {
    suspend fun selectSource(): CloudSelection

    suspend fun selectDestinationFolder(): CloudSelection
}
