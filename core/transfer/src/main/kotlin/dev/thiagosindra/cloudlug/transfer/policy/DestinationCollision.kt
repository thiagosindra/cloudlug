package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.provider.CloudObject

/** What the destination check decided about one item (spec §19.3). */
sealed interface CollisionOutcome {

    /** Nothing is in the way; the bytes may move. */
    data object Upload : CollisionOutcome

    /** Equivalent content is provably already there (spec §19.1). */
    data class SkipDuplicate(
        val destination: CloudObject,
        val reason: ItemStatusReason = ItemStatusReason.DUPLICATE_VERIFIED_BY_HASH,
    ) : CollisionOutcome

    /** Something is in the way and CloudLug cannot prove it is the same file. */
    data class Conflict(val reason: ItemStatusReason) : CollisionOutcome
}

/**
 * The destination collision algorithm of spec §19.3, exactly as written.
 *
 * ```
 * no match           -> upload
 * multiple matches   -> CONFLICT
 * one match:
 *   size differs     -> CONFLICT
 *   size equal:
 *     strong hash available on both sides and equal   -> SKIPPED_DUPLICATE
 *     strong hash available on both sides and differs -> CONFLICT
 *     no comparable hash                              -> CONFLICT
 * ```
 *
 * Note what this does *not* do: it never overwrites, never renames and never
 * guesses (spec §2.5, §32.2). "No comparable hash" is a conflict rather than a
 * skip, so a Dropbox source hash and a Drive MD5 — which say nothing about each
 * other (spec §19.4) — stop the item instead of silently resolving it.
 *
 * Callers consult the §19.2 idempotency record *before* this: an item this
 * transfer already completed is skipped as ALREADY_TRANSFERRED and never
 * reaches the collision algorithm, which would otherwise report a conflict
 * against CloudLug's own upload.
 */
object DestinationCollisionResolver {

    /**
     * @param matches everything `lookupDestination` returned for the name
     * @param sourceSize size reported by the source, or null when unknown
     * @param comparableHashes every hash CloudLug holds for the source bytes:
     *   the source provider's hash, and — when the item has already been
     *   streamed once — the locally computed SHA-256 and destination-native
     *   hash. Only hashes sharing an algorithm with the destination's are used.
     */
    fun resolve(
        matches: List<CloudObject>,
        sourceSize: Long?,
        comparableHashes: List<ProviderHash>,
    ): CollisionOutcome {
        if (matches.isEmpty()) return CollisionOutcome.Upload
        if (matches.size > 1) return CollisionOutcome.Conflict(ItemStatusReason.CONFLICT_MULTIPLE_MATCHES)

        val destination = matches.single()
        if (sourceSize == null || destination.size == null || destination.size != sourceSize) {
            return CollisionOutcome.Conflict(ItemStatusReason.CONFLICT_SIZE_DIFFERS)
        }

        val destinationHash = destination.providerHash
            ?: return CollisionOutcome.Conflict(ItemStatusReason.CONFLICT_NO_COMPARABLE_HASH)
        val ours = comparableHashes.firstOrNull { it.comparableTo(destinationHash) }
            ?: return CollisionOutcome.Conflict(ItemStatusReason.CONFLICT_NO_COMPARABLE_HASH)

        return if (ours.matches(destinationHash)) {
            CollisionOutcome.SkipDuplicate(destination)
        } else {
            CollisionOutcome.Conflict(ItemStatusReason.CONFLICT_HASH_DIFFERS)
        }
    }
}
