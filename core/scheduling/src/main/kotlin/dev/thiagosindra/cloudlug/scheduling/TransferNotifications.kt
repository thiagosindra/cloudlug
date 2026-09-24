package dev.thiagosindra.cloudlug.scheduling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.ui.bytesAreLowerBound
import dev.thiagosindra.cloudlug.ui.directionLabel
import dev.thiagosindra.cloudlug.ui.formatBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The §24.4 notification: what is moving, how far it has got, and the two
 * controls that act on it.
 *
 * Not optional on API 34+. A user-initiated data transfer job **must** post one
 * through `JobService.setNotification`, and the platform's own documentation
 * says to put action buttons on it: a job the user stops from the Task Manager
 * cannot be rescheduled by the app, so a graceful pause has to be reachable
 * from here or the only way out is the one that cannot be undone.
 */
@Singleton
class TransferNotifications @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of a running transfer, with pause and cancel."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Stable per transfer, so an update replaces rather than stacks. */
    fun notificationId(id: dev.thiagosindra.cloudlug.model.TransferId): Int = id.value.hashCode()

    /**
     * @param accountNames disambiguates two accounts at one provider, the same
     *   way §24.1 and §24.3 do; the notification is a third place the direction
     *   is shown and it must not be the one that is ambiguous.
     */
    fun build(
        transfer: TransferEntity,
        current: TransferItemEntity?,
        accountNames: Map<dev.thiagosindra.cloudlug.model.AccountId, String> = emptyMap(),
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL)
            .setContentTitle(transfer.directionLabel(accountNames))
            .setContentText(line(transfer, current))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
            .setOngoing(!transfer.status.isTerminal)

        if (transfer.totalFiles > 0 && transfer.status == TransferStatus.RUNNING) {
            builder.setProgress(transfer.totalFiles, transfer.completedFiles, transfer.bytesAreLowerBound())
        }

        // §22.1 and §22.3, reachable without opening the app — which is the
        // point, since the transfer that needs stopping is the one running
        // while the phone is in a pocket.
        builder.addAction(action("Pause", TransferAction.PAUSE, transfer))
        builder.addAction(action("Cancel", TransferAction.CANCEL, transfer))
        return builder.build()
    }

    /**
     * §24.4: "waiting states explicitly say why the transfer is paused".
     *
     * A bare "Paused" is the failure this guards against — it tells the user
     * something stopped and nothing about whether they can do anything.
     */
    private fun line(transfer: TransferEntity, current: TransferItemEntity?): String = when (transfer.status) {
        TransferStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
        TransferStatus.WAITING_FOR_STORAGE -> "Waiting for space on this device"
        TransferStatus.AUTH_REQUIRED -> "Sign in again to continue"
        TransferStatus.PAUSED -> "Paused"
        TransferStatus.PREPARING -> "Preparing"
        TransferStatus.RUNNING -> current?.filename
            ?: "${transfer.completedFiles} of ${transfer.totalFiles} files"

        else -> "${transfer.completedFiles} of ${transfer.totalFiles} files, " +
            formatBytes(transfer.completedBytes)
    }

    private fun action(label: String, action: TransferAction, transfer: TransferEntity): Notification.Action {
        val intent = Intent(context, TransferActionReceiver::class.java).apply {
            this.action = action.name
            putExtra(TransferActionReceiver.EXTRA_TRANSFER_ID, transfer.id.value)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            (transfer.id.value + action.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pending).build()
    }

    internal companion object {
        const val CHANNEL = "cloudlug.transfers"
    }
}

internal enum class TransferAction { PAUSE, CANCEL }
