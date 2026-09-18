package dev.thiagosindra.cloudlug.model

/**
 * Transfer-level state (spec §13.1).
 *
 * Legal transitions are enforced in `:core:database`
 * (`dev.thiagosindra.cloudlug.database.state.TransferStateMachine`), not here,
 * so that persistence and validation cannot drift apart.
 */
enum class TransferStatus {
    DRAFT,
    PREPARING,
    READY,
    RUNNING,
    PAUSED,
    WAITING_FOR_WIFI,
    WAITING_FOR_STORAGE,
    AUTH_REQUIRED,
    FAILED,
    CANCELLED,
    COMPLETED,

    /**
     * At least one item did not reach COMPLETED or SKIPPED_DUPLICATE (spec
     * §13.1). A transfer that moved half of what the user selected must never
     * read "Completed" (§2.5), so unsupported, source-changed, conflicted,
     * failed and cancelled items all land here and the summary shows each count
     * separately. Named ISSUES rather than ERRORS because a skipped Google-native
     * document is not an error.
     */
    COMPLETED_WITH_ISSUES,
    ;

    /** True when the transfer will not change state again without user action. */
    val isTerminal: Boolean
        get() = this == FAILED || this == CANCELLED || this == COMPLETED || this == COMPLETED_WITH_ISSUES

    /** True when the transfer is holding, waiting for a condition to clear (spec §16, §15.1, §23). */
    val isWaiting: Boolean
        get() = this == WAITING_FOR_WIFI || this == WAITING_FOR_STORAGE || this == AUTH_REQUIRED
}

/**
 * Per-object state (spec §13.2).
 *
 * Every manifest object ends in exactly one terminal state with a recorded
 * reason (spec §32.9), which is why [TransferItemStatus.isTerminal] and
 * [ItemStatusReason] are part of the model rather than free-text.
 */
enum class TransferItemStatus {
    PENDING,
    CHECKING_DESTINATION,
    DOWNLOADING,
    CACHED,
    UPLOADING,
    VERIFYING,
    COMPLETED,
    SKIPPED_DUPLICATE,
    SKIPPED_UNSUPPORTED,
    CONFLICT,
    SOURCE_CHANGED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this in TERMINAL

    /** True while the item is actively moving bytes or metadata. */
    val isActive: Boolean
        get() = !isTerminal && this != PENDING

    private companion object {
        val TERMINAL = setOf(
            COMPLETED,
            SKIPPED_DUPLICATE,
            SKIPPED_UNSUPPORTED,
            CONFLICT,
            SOURCE_CHANGED,
            FAILED,
            CANCELLED,
        )
    }
}

/**
 * Why an item reached its state. Persisted as `statusReason` (spec §12.2) so
 * that the UI can explain an outcome without re-deriving it, and so history
 * survives an app upgrade that changes user-facing wording.
 */
enum class ItemStatusReason {
    /** Destination object exists with an equal strong hash (spec §19.3). */
    DUPLICATE_VERIFIED_BY_HASH,

    /** A previous run of this transfer already completed this item (spec §19.2). */
    ALREADY_TRANSFERRED,

    /** More than one destination sibling carries this name (spec §19.3). */
    CONFLICT_MULTIPLE_MATCHES,

    /** Destination object exists with a different size (spec §19.3). */
    CONFLICT_SIZE_DIFFERS,

    /** Destination object exists, sizes match, comparable hashes differ (spec §19.3). */
    CONFLICT_HASH_DIFFERS,

    /** Destination object exists and nothing comparable proves it identical (spec §19.3). */
    CONFLICT_NO_COMPARABLE_HASH,

    /** Two source objects map to the same destination name (spec §20.3). */
    CONFLICT_CASE_INSENSITIVE_COLLISION,

    /** The name cannot be represented at the destination (spec §20.4). */
    CONFLICT_ILLEGAL_NAME,

    /** Provider-native document with no byte stream (spec §20.1). */
    UNSUPPORTED_PROVIDER_NATIVE_DOCUMENT,

    /** Provider-native document larger than the provider's export cap (spec §20.1). */
    UNSUPPORTED_EXPORT_TOO_LARGE,

    /** Shortcut or link object; not followed (spec §20.2). */
    UNSUPPORTED_SHORTCUT,

    /** Source revision changed after the user reviewed the manifest (spec §20.6). */
    SOURCE_REVISION_CHANGED,

    /** Destination hash equals the locally computed native hash (spec §21 step 1–2). */
    VERIFIED_BY_DESTINATION_HASH,

    /** Only size could be confirmed; provider declares no server hash (spec §21 step 4). */
    VERIFIED_BY_SIZE_ONLY,

    /** Retry budget exhausted or a permanent provider error (spec §23). */
    ERROR_PERMANENT,

    /** Cancelled by the user (spec §22.2, §22.3). */
    CANCELLED_BY_USER,
}

/** Lifecycle of one cached byte range (spec §15.3). */
enum class CacheChunkStatus {
    ALLOCATED,
    DOWNLOADING,
    READY,
    UPLOADING,
    ACKNOWLEDGED,
    DELETED,
}
