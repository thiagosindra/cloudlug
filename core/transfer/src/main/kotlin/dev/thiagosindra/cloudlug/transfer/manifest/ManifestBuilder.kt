package dev.thiagosindra.cloudlug.transfer.manifest

import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities
import dev.thiagosindra.cloudlug.provider.SelectionRoot
import kotlinx.coroutines.flow.collect
import java.time.Clock
import java.util.UUID

/** What the manifest ended up containing, for the review step (spec §24.2). */
data class ManifestSummary(
    val folders: Int = 0,
    val files: Int = 0,
    val bytes: Long = 0,
    val unsupported: Int = 0,
    val conflicts: Int = 0,
) {
    /** Items that will actually move bytes. */
    val transferableFiles: Int get() = files - unsupported - conflicts
}

/**
 * Walks the source selection and persists the logical manifest (spec §11).
 *
 * Two properties matter more than speed here:
 *
 *  - **It is resumable.** Items are written in pages, each page in one
 *    transaction with the enumeration cursor, so a process killed mid-walk
 *    resumes without gaps or duplicates (spec §11, §12.1).
 *  - **It decides everything it can before any byte moves.** Unsupported
 *    objects (§20.1, §20.2), illegal names (§20.4) and sibling collisions
 *    (§20.3) are classified here, so the user sees them in review rather than
 *    discovering them mid-transfer (spec §24.2).
 *
 * Relative paths come from the source tree and are reproduced under the
 * enclosing folder, empty folders included (§10, §20.5).
 */
class ManifestBuilder(
    private val repository: TransferRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val pageSize: Int = 200,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Walks [selection] and writes the manifest.
     *
     * Each root lands where its [SelectionRoot.displayPath] says: §10 preserves
     * the source's own ancestors, so a selection of `/photos/2026/April` lands at
     * `photos/2026/April`. Only the picker knows that path, since [CloudObject]
     * carries identity rather than a path (§6, §9) — which is why it travels
     * with the selection instead of through the `rootPathResolver` callback
     * ADR-0014 used.
     *
     * [resumeAfter] continues an enumeration that process death interrupted; it
     * is the last object ID this transfer persisted (spec §11).
     */
    suspend fun build(
        transfer: TransferEntity,
        source: CloudProvider,
        destinationCapabilities: ProviderCapabilities,
        selection: CloudSelection,
        resumeAfter: CloudObjectId? = null,
    ): ManifestSummary {
        val state = BuildState(destinationCapabilities)
        val paths = mutableMapOf<CloudObjectId, CloudPath>()
        val roots = selection.roots.associateBy { it.cloudObject.id }

        source.enumerate(selection.accountId, selection, resumeAfter).collect { obj ->
            val parentPath = when {
                obj.id in roots -> roots.getValue(obj.id).displayPath.parent ?: CloudPath.ROOT
                else -> paths[obj.parentId]
                    ?: error("Adapter emitted ${obj.id.opaqueId} before its parent (spec §5: depth-first)")
            }
            val path = parentPath.child(obj.name)
            paths[obj.id] = path

            state.add(classify(transfer, obj, path, destinationCapabilities, state))
            // The cursor is the last emitted object's ID (spec §11), persisted
            // in the same transaction as the page it belongs to.
            if (state.pending.size >= pageSize) flush(transfer, state, cursor = obj.id.opaqueId)
        }

        flush(transfer, state, cursor = null)
        return state.summary
    }

    private suspend fun flush(transfer: TransferEntity, state: BuildState, cursor: String?) {
        if (state.pending.isEmpty() && cursor == null) return
        repository.appendManifestItems(transfer.id, state.pending.toList(), cursor)
        // Siblings discovered after an earlier page was written still have to be
        // marked (spec §20.3); the earlier row is already persisted.
        state.deferredConflicts.forEach { (itemId, reason) ->
            repository.transitionItem(itemId, TransferItemStatus.CONFLICT, reason)
        }
        state.deferredConflicts.clear()
        state.pending.clear()
    }

    private fun classify(
        transfer: TransferEntity,
        obj: CloudObject,
        path: CloudPath,
        capabilities: ProviderCapabilities,
        state: BuildState,
    ): TransferItemEntity {
        val now = clock.instant()
        val base = TransferItemEntity(
            id = TransferItemId(idFactory()),
            transferId = transfer.id,
            sourceObjectId = obj.id.opaqueId,
            sourceRevision = obj.revision,
            sourceRelativePath = path,
            filename = obj.name,
            mimeType = obj.mimeType,
            size = obj.size,
            modifiedAt = obj.modifiedAt,
            objectKind = obj.type,
            sourceProviderHash = obj.providerHash,
            destinationRelativePath = path,
            createdAt = now,
            updatedAt = now,
        )

        DestinationNameLegality.check(obj.name, capabilities)?.let { reason ->
            return base.copy(status = TransferItemStatus.CONFLICT, statusReason = reason)
        }

        state.claimName(path, capabilities, base.id)?.let { reason ->
            return base.copy(status = TransferItemStatus.CONFLICT, statusReason = reason)
        }

        return when (obj.type) {
            // §20.2: shortcuts are not followed.
            CloudObjectType.SHORTCUT -> base.copy(
                status = TransferItemStatus.SKIPPED_UNSUPPORTED,
                statusReason = ItemStatusReason.UNSUPPORTED_SHORTCUT,
            )

            // §20.1: no byte stream. The optional "export on transfer" setting
            // is TODO for the Drive adapter milestone; the default is to skip.
            CloudObjectType.PROVIDER_NATIVE_DOCUMENT -> base.copy(
                status = TransferItemStatus.SKIPPED_UNSUPPORTED,
                statusReason = ItemStatusReason.UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,
            )

            CloudObjectType.FOLDER, CloudObjectType.FILE -> base
        }
    }

    /** Accumulates a page plus the cross-page bookkeeping the classification needs. */
    private class BuildState(private val capabilities: ProviderCapabilities) {
        val pending = mutableListOf<TransferItemEntity>()
        val deferredConflicts = mutableListOf<Pair<TransferItemId, ItemStatusReason>>()
        private val claimedNames = mutableMapOf<String, TransferItemId>()
        var summary = ManifestSummary()
            private set

        /**
         * Registers [path] as taken, or reports the collision (spec §20.3).
         *
         * Names are compared case-insensitively when the destination folds case
         * or forbids duplicate siblings — the Drive-to-Dropbox direction §20.3
         * calls out. The *earlier* item is marked too: neither of two colliding
         * siblings is more entitled to the name than the other, and picking one
         * silently would be a rename by another route.
         */
        fun claimName(
            path: CloudPath,
            capabilities: ProviderCapabilities,
            itemId: TransferItemId,
        ): ItemStatusReason? {
            val foldCase = !capabilities.caseSensitiveNames || !capabilities.allowsDuplicateSiblingNames
            val key = if (foldCase) path.caseFoldedKey() else path.toString()
            val previous = claimedNames.putIfAbsent(key, itemId) ?: return null

            val reason = ItemStatusReason.CONFLICT_CASE_INSENSITIVE_COLLISION
            val alreadyPending = pending.indexOfFirst { it.id == previous }
            if (alreadyPending >= 0) {
                val item = pending[alreadyPending]
                if (item.status != TransferItemStatus.CONFLICT) {
                    pending[alreadyPending] = item.copy(status = TransferItemStatus.CONFLICT, statusReason = reason)
                    recount(item.status, item, -1)
                    recount(TransferItemStatus.CONFLICT, item, +1)
                }
            } else {
                deferredConflicts += previous to reason
            }
            return reason
        }

        fun add(item: TransferItemEntity) {
            pending += item
            recount(item.status, item, +1)
        }

        private fun recount(status: TransferItemStatus, item: TransferItemEntity, delta: Int) {
            summary = when {
                item.objectKind == CloudObjectType.FOLDER && status != TransferItemStatus.CONFLICT ->
                    summary.copy(folders = summary.folders + delta)

                else -> summary.copy(
                    files = summary.files + delta,
                    bytes = summary.bytes + delta * (item.size ?: 0L),
                    unsupported = summary.unsupported +
                        if (status == TransferItemStatus.SKIPPED_UNSUPPORTED) delta else 0,
                    conflicts = summary.conflicts +
                        if (status == TransferItemStatus.CONFLICT) delta else 0,
                )
            }
        }

        init {
            require(capabilities.canBeDestination) { "The chosen account cannot act as a destination" }
        }
    }
}
