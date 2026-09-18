package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudPath

/**
 * One object the user picked, with where it belongs at the destination.
 *
 * [CloudObject] carries identity rather than a path (spec §6), so the engine
 * cannot derive a root's ancestors from the object alone. §10 nonetheless maps
 * a selection of `/photos/2026/April` to `photos/2026/April` under the enclosing
 * folder. The picker browsed to the object and is the only component that knows
 * its display path, so it supplies it here (spec §9).
 *
 * This replaces the `rootPathResolver` callback of ADR-0014: the path is data
 * that travels with the selection, not a function the engine has to be handed.
 */
data class SelectionRoot(
    val cloudObject: CloudObject,
    /** Destination-relative path of this root, e.g. `photos/2026/April`. */
    val displayPath: CloudPath,
) {
    init {
        require(!displayPath.isRoot) { "A selected root needs a non-empty display path" }
    }
}

/**
 * What the user picked on one side of a transfer (spec §9).
 *
 * A selection may be a single file, several files, one or more directories, or
 * a mixture, subject to
 * [ProviderCapabilities.supportsMultipleSourceSelection]. The transfer engine
 * must not care how the selection was obtained — in-app browser, provider
 * picker or a future mechanism.
 *
 * Enumeration resumes through `enumerate(resumeAfter = ...)` rather than a
 * cursor stored here (spec §5, §11); see ADR-0006, which v1.2 overrules.
 */
data class CloudSelection(
    val accountId: AccountId,
    val roots: List<SelectionRoot>,
) {
    init {
        require(roots.isNotEmpty()) { "A selection must contain at least one object" }
        require(roots.all { it.cloudObject.id.provider == roots.first().cloudObject.id.provider }) {
            "A selection must not mix providers"
        }
    }

    val objects: List<CloudObject> get() = roots.map { it.cloudObject }

    val provider get() = roots.first().cloudObject.id.provider

    companion object {
        /**
         * A selection whose roots land at the destination under their own
         * names — the shape a flat picker produces, and the §10 default when no
         * ancestors are being preserved.
         */
        fun of(accountId: AccountId, roots: List<CloudObject>): CloudSelection =
            CloudSelection(accountId, roots.map { SelectionRoot(it, CloudPath.of(it.name)) })

        fun of(accountId: AccountId, vararg roots: CloudObject): CloudSelection =
            of(accountId, roots.toList())
    }
}

/**
 * Obtains selections from the user (spec §9). Implemented by the UI layer; the
 * engine depends on the resulting [CloudSelection] only.
 */
interface CloudSelectionProvider {
    suspend fun selectSource(): CloudSelection

    suspend fun selectDestinationFolder(): CloudSelection
}
