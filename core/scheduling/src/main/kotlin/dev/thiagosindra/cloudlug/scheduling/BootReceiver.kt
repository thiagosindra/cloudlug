package dev.thiagosindra.cloudlug.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Puts the schedule back together after a reboot (spec §2.4).
 *
 * A persisted UIDT job should survive the reboot on its own — `setPersisted` is
 * permitted alongside `setUserInitiated`, which the platform's own validation
 * confirms — so on 34+ this is usually a no-op that finds the job already
 * there. It stays because "usually" is not a guarantee, and because the
 * WorkManager path needs it.
 *
 * It cannot schedule a *new* UIDT job: those may only be scheduled while the
 * app is visible or otherwise allowed to start an activity, and a boot receiver
 * is neither. `reconcile` handles that by leaving such a transfer as it is and
 * saying so in the notification, rather than failing silently.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: TransferScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.reconcile()
            } finally {
                pending.finish()
            }
        }
    }
}
