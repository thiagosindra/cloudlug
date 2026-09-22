package dev.thiagosindra.cloudlug.database

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object TestFixtures {

    val EPOCH: Instant = Instant.parse("2026-09-17T09:57:00Z")

    fun clock(at: Instant = EPOCH): Clock = Clock.fixed(at, ZoneOffset.UTC)

    fun transfer(
        id: String = "t1",
        source: ProviderType = ProviderType.DROPBOX,
        destination: ProviderType = ProviderType.GOOGLE_DRIVE,
        sourceAccount: String = "source-account",
        destinationAccount: String = "destination-account",
    ) = TransferEntity(
        id = TransferId(id),
        createdAt = EPOCH,
        updatedAt = EPOCH,
        sourceProvider = source,
        sourceAccountId = AccountId(sourceAccount),
        destinationProvider = destination,
        destinationAccountId = AccountId(destinationAccount),
        destinationRootId = "destination-root",
        destinationContainerName = "CloudLug - 2026-09-17 09-57",
    )

    fun item(
        id: String,
        transferId: String = "t1",
        path: String = "docs/abc.txt",
        size: Long? = 1_024,
        kind: CloudObjectType = CloudObjectType.FILE,
        status: TransferItemStatus = TransferItemStatus.PENDING,
        sourceObjectId: String = "src-$id",
        revision: String? = "rev-1",
    ) = TransferItemEntity(
        id = TransferItemId(id),
        transferId = TransferId(transferId),
        sourceObjectId = sourceObjectId,
        sourceRevision = revision,
        sourceRelativePath = CloudPath.parse(path),
        filename = CloudPath.parse(path).name.orEmpty(),
        size = size,
        objectKind = kind,
        status = status,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )
}
