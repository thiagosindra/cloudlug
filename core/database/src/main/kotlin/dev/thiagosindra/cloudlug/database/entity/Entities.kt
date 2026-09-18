package dev.thiagosindra.cloudlug.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CacheChunkId
import dev.thiagosindra.cloudlug.model.CacheChunkStatus
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import java.time.Instant

/*
 * The persistence model of spec §12.
 *
 * These are plain Kotlin classes, not Room entities: v0.1 is a JVM-only build
 * (see docs/decisions.md ADR-0002 and ADR-0003). Field names and shapes are the
 * ones §12 specifies so that annotating them — plus TypeConverters for the
 * value classes, enums, Instant, CloudPath and ProviderHash — is the whole of
 * the Room migration in v0.2.
 *
 * TODO(§33 v0.2): add @Entity/@PrimaryKey/@ForeignKey and the converters; the
 * DAO interfaces in `dao/` are already shaped for Room to implement.
 */

/** A transfer: two accounts, one enclosing destination folder, one manifest (spec §12.1). */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: TransferId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourceProvider: ProviderType,
    val sourceAccountId: AccountId,
    val destinationProvider: ProviderType,
    val destinationAccountId: AccountId,
    /** The parent folder the user picked at the destination. */
    val destinationRootId: String,
    /** The enclosing "CloudLug - <date> <time>" folder, once created (spec §10). */
    val destinationContainerId: String? = null,
    val destinationContainerName: String,
    val status: TransferStatus = TransferStatus.DRAFT,
    val networkPolicy: TransferNetworkPolicy = TransferNetworkPolicy.UNMETERED_ONLY,
    /** Opaque provider cursor so a long enumeration survives process death (spec §11). */
    val enumerationCursor: String? = null,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    /** Items a previous run already transferred, or already present and provably identical (§19.3). */
    val duplicateFiles: Int = 0,
    /** Items that cannot move as bytes: native documents, shortcuts (§20.1, §20.2). */
    val unsupportedFiles: Int = 0,
    /** Items whose source revision changed after the user reviewed the manifest (§20.6). */
    val sourceChangedFiles: Int = 0,
    val conflictFiles: Int = 0,
    val failedFiles: Int = 0,
    val cancelledFiles: Int = 0,
    /** Sum of known sizes only; see [unknownSizeFiles] (spec §11). */
    val totalBytes: Long = 0,
    val completedBytes: Long = 0,
    /**
     * Manifest items with no size before transfer — provider-native documents
     * awaiting export (spec §11, §20.1). While this is non-zero the UI drives
     * progress by file count and shows bytes as "at least", because
     * [totalBytes] is a lower bound.
     */
    @ColumnInfo(defaultValue = "0")
    val unknownSizeFiles: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
) {
    init {
        require(sourceProvider != destinationProvider) {
            "Same-provider transfers are prohibited by the transfer domain (spec §2.2)"
        }
    }

    /** Items that reached a terminal state, however they got there (spec §32.9). */
    val settledFiles: Int
        get() = completedFiles + duplicateFiles + unsupportedFiles + sourceChangedFiles +
            conflictFiles + failedFiles + cancelledFiles

    /**
     * True when every settled item ended COMPLETED or SKIPPED_DUPLICATE, which
     * is the only way a transfer may read "Completed" (spec §13.1).
     */
    val settledCleanly: Boolean
        get() = unsupportedFiles == 0 && sourceChangedFiles == 0 &&
            conflictFiles == 0 && failedFiles == 0 && cancelledFiles == 0

    /** True while [totalBytes] is a lower bound rather than the whole job (spec §11). */
    val hasUnknownSizes: Boolean
        get() = unknownSizeFiles > 0
}

/** One object in the manifest: a file, a folder, or something that cannot move (spec §12.2). */
@Entity(
    tableName = "transfer_items",
    foreignKeys = [
        ForeignKey(
            entity = TransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("transferId"),
        // §19.2 looks an item up by its source object across transfers, and
        // §11 deduplicates by it within one; both are hot enough to index.
        Index("sourceObjectId"),
        Index(value = ["transferId", "status"]),
    ],
)
data class TransferItemEntity(
    @PrimaryKey val id: TransferItemId,
    val transferId: TransferId,
    val sourceObjectId: String,
    /** Revision recorded at enumeration; compared again before download (spec §20.6). */
    val sourceRevision: String? = null,
    val sourceRelativePath: CloudPath,
    val filename: String,
    val mimeType: String? = null,
    /** Null for provider-native documents, which report no size (spec §20.1). */
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    val objectKind: CloudObjectType,
    val sourceProviderHash: ProviderHash? = null,
    val computedSha256: ProviderHash? = null,
    val computedDestinationNativeHash: ProviderHash? = null,
    /**
     * Serialized hasher state as of the last acknowledged chunk (spec §12.2,
     * §19.4), written in the same transaction as that acknowledgment (§15.3) so
     * hashing resumes after process death instead of needing chunks the cache
     * has already deleted.
     */
    val hashCheckpoint: HashCheckpoint? = null,
    val destinationParentId: String? = null,
    val destinationObjectId: String? = null,
    val destinationRelativePath: CloudPath? = null,
    val status: TransferItemStatus = TransferItemStatus.PENDING,
    val statusReason: ItemStatusReason? = null,
    val downloadedBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val uploadSessionId: String? = null,
    /** Opaque provider payload needed to resume the session (spec §22.5). */
    val uploadSessionMetadata: String? = null,
    val uploadSessionExpiresAt: Instant? = null,
    val retryCount: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** One cached byte range on local storage (spec §12.3, §15.3). */
@Entity(
    tableName = "cache_chunks",
    foreignKeys = [
        ForeignKey(
            entity = TransferItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transferItemId"), Index(value = ["transferItemId", "status"])],
)
data class CacheChunkEntity(
    @PrimaryKey val id: CacheChunkId,
    val transferItemId: TransferItemId,
    val offset: Long,
    val length: Long,
    /** File name inside the item's cache directory, e.g. `00000000.chunk` (spec §15.2). */
    val localFilename: String,
    /**
     * Digest of this range. Populated for detecting a corrupt chunk on resume;
     * it is not the object hash, which the pipeline computes in one pass
     * (spec §19.4).
     */
    val hash: ProviderHash? = null,
    val status: CacheChunkStatus = CacheChunkStatus.ALLOCATED,
    val createdAt: Instant,
) {
    init {
        require(offset >= 0) { "Chunk offset must not be negative" }
        require(length >= 0) { "Chunk length must not be negative" }
    }

    val endExclusive: Long get() = offset + length
}

/**
 * Non-secret account metadata (spec §12.4).
 *
 * Refresh tokens and any other long-lived secret live in Keystore-protected
 * storage, never here (spec §8.3).
 */
@Entity(tableName = "accounts", indices = [Index(value = ["provider", "providerAccountId"], unique = true)])
data class AccountEntity(
    @PrimaryKey val id: AccountId,
    val provider: ProviderType,
    val providerAccountId: String,
    val displayName: String? = null,
    val displayEmail: String? = null,
    val grantedScopes: Set<String> = emptySet(),
    val createdAt: Instant,
)
