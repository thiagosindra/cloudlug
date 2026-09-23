package dev.thiagosindra.cloudlug.ui

import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.TransferStatus
import java.util.Locale

/** Byte counts as §24.1 and §24.3 show them: `31.4 GB`, `381 MB`, `742 B`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

fun formatCount(count: Int): String = String.format(Locale.US, "%,d", count)

/**
 * Progress as a fraction in 0..1.
 *
 * Spec §11: while any item's size is unknown, `totalBytes` is only a lower
 * bound, so the denominator is file count instead. Driving a bar from a
 * denominator that grows as sizes resolve makes it run backwards.
 */
fun TransferEntity.progressFraction(): Float = when {
    totalFiles == 0 -> 0f
    hasUnknownSizes || totalBytes == 0L -> settledFiles.toFloat() / totalFiles
    else -> (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Whether byte totals should read "at least" (spec §11). */
fun TransferEntity.bytesAreLowerBound(): Boolean = hasUnknownSizes

/**
 * The one-line outcome §24.1 puts in the history list.
 *
 * Each non-success outcome is named separately, because §13.1 counts them
 * separately and "3 failed, 12 skipped" tells the user what to do next in a way
 * that a single "finished with issues" does not.
 */
fun TransferEntity.summaryLine(): String = when (status) {
    TransferStatus.COMPLETED -> "Completed"
    TransferStatus.COMPLETED_WITH_ISSUES -> buildList {
        if (failedFiles > 0) add("${formatCount(failedFiles)} failed")
        if (conflictFiles > 0) add("${formatCount(conflictFiles)} in conflict")
        if (unsupportedFiles > 0) add("${formatCount(unsupportedFiles)} skipped")
        if (sourceChangedFiles > 0) add("${formatCount(sourceChangedFiles)} changed at source")
        if (cancelledFiles > 0) add("${formatCount(cancelledFiles)} cancelled")
    }.joinToString(", ").ifEmpty { "Completed with issues" }

    TransferStatus.FAILED -> "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
    TransferStatus.PAUSED -> "Paused"
    TransferStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    TransferStatus.WAITING_FOR_STORAGE -> "Waiting for storage"
    TransferStatus.AUTH_REQUIRED -> "Sign-in needed"
    TransferStatus.RUNNING -> "Running"
    TransferStatus.READY -> "Ready to start"
    TransferStatus.PREPARING -> "Preparing"
    TransferStatus.DRAFT -> "Draft"
}

fun providerLabel(type: dev.thiagosindra.cloudlug.model.ProviderType): String = when (type) {
    dev.thiagosindra.cloudlug.model.ProviderType.DROPBOX -> "Dropbox"
    dev.thiagosindra.cloudlug.model.ProviderType.GOOGLE_DRIVE -> "Google Drive"
    dev.thiagosindra.cloudlug.model.ProviderType.FAKE -> "Demo provider"
}

/**
 * How §24.1 and §24.3 name a transfer's two ends.
 *
 * The provider alone was enough while a provider meant an account. Since v0.4
 * it does not: two Dropbox accounts are a legal pair (§2.2 as amended), and
 * "Dropbox -> Dropbox" is the same string for every transfer between them and
 * for the reverse direction. Two such transfers in the list were
 * indistinguishable.
 *
 * So when the two ends share a provider, each is named by its account —
 * whichever of email or display name §24.5 recorded. When they do not, the
 * provider names are already unambiguous and adding addresses would only make
 * the row longer.
 *
 * [accountNames] is a plain map rather than the account domain type so that
 * `core:ui` need not depend on the provider API for a label; a caller supplies
 * whatever it already has.
 */
fun TransferEntity.directionLabel(accountNames: Map<AccountId, String> = emptyMap()): String {
    val source = providerLabel(sourceProvider)
    val destination = providerLabel(destinationProvider)
    if (sourceProvider != destinationProvider) return "$source -> $destination"

    // A disconnected account leaves no row behind (§8.3), so its name can be
    // gone while the transfer it ran is still in the history. Falling back to
    // the account id keeps the two ends distinguishable, which is the whole
    // point here, and matches what §24.5 shows for an account with no address.
    val from = accountNames[sourceAccountId] ?: sourceAccountId.value
    val to = accountNames[destinationAccountId] ?: destinationAccountId.value
    return "$source ($from) -> $destination ($to)"
}
