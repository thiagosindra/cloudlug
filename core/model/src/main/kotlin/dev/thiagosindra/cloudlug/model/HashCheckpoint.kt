package dev.thiagosindra.cloudlug.model

/**
 * A serialized snapshot of a hasher's internal state (spec §19.4).
 *
 * CloudLug persists one of these as `TransferItemEntity.hashCheckpoint` in the
 * same transaction as each chunk acknowledgment (spec §15.3), so that hashing
 * resumes exactly where it stopped after process death. Without it, an item
 * whose acknowledged chunks were deleted from the cache could not finish
 * computing the destination-native hash, and verification would degrade down
 * the §21 ordering — which, since v1.2 deleted the permissive step 3, now means
 * the item cannot complete at all.
 *
 * The payload is opaque to everything above `:core:hashing`: it is produced by
 * `StreamingHasher.checkpoint()` and consumed by `Hashers.restore()`. It is
 * deliberately a [String] so the persistence layer stores one text column and
 * never needs to know which algorithm produced it.
 */
@JvmInline
value class HashCheckpoint(val encoded: String) {
    init {
        require(encoded.isNotBlank()) { "Hash checkpoint must not be blank" }
    }
}
