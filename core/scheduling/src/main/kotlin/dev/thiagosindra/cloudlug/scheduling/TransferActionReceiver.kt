package dev.thiagosindra.cloudlug.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.transfer.TransferController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The §24.4 notification's Pause and Cancel buttons.
 *
 * These exist because of a platform rule as much as a spec one: a
 * user-initiated job that the user stops from the Task Manager **cannot be
 * rescheduled by the app**. Without a graceful pause here, the only way to
 * stop a running transfer from outside the app is the one that cannot be
 * undone.
 */
@AndroidEntryPoint
class TransferActionReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TransferController

    @Inject lateinit var scheduler: TransferScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val id = TransferId(intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return)
        val action = runCatching { TransferAction.valueOf(intent.action.orEmpty()) }.getOrNull() ?: return
        val pending = goAsync()

        // The receiver's own window is short, so the work is handed to a scope
        // and the result awaited through goAsync rather than blocking here.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    // Cancel the platform's job first in both cases: leaving it
                    // scheduled would have it start again the moment the
                    // controller let go.
                    TransferAction.PAUSE -> {
                        scheduler.cancel(id)
                        controller.pause(id)
                    }

                    TransferAction.CANCEL -> {
                        scheduler.cancel(id)
                        controller.cancel(id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val EXTRA_TRANSFER_ID = "transferId"
    }
}
