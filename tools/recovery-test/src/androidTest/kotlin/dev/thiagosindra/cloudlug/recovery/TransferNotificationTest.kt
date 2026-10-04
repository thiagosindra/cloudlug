package dev.thiagosindra.cloudlug.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.fail

/**
 * §24.4: the notification is the *active* transfer's, and a finished transfer
 * takes it down.
 *
 * The first real Dropbox → Google Drive run, on a Samsung phone,
 * completed and verified its file while the shade went on showing it mid-run:
 * full progress bar, "1 of 1 files", Pause and Cancel, and not dismissable. A
 * user-initiated job owns its notification while it runs, ignores the app's
 * own cancel, and its end policy then decides what is left behind.
 *
 * The title is looked for in the system UI's package, not CloudLug's, so the
 * §24.3 screen showing the same words cannot satisfy either half. The first
 * half is what makes the second mean something (`docs/testing.md` rule 2): a
 * selector that never matched the notification would pass "it is gone" for
 * free.
 */
@RunWith(AndroidJUnit4::class)
class TransferNotificationTest {

    private val app = CloudLug()

    @Before
    fun startClean() {
        app.clearData()
        app.setWifi(true)
    }

    @Test
    fun a_completed_transfer_leaves_no_notification_behind() {
        app.launch(pacePerChunkMillis = CloudLug.PACE_MS)
        app.startDemoTransfer()
        app.awaitMidFile()

        app.device.openNotification()
        if (!app.device.wait(Until.hasObject(NOTIFICATION), SHADE_TIMEOUT)) {
            fail("the shade never showed the running transfer, so its absence later would prove nothing.\n${app.visibleText()}")
        }
        app.device.pressBack()

        app.awaitCleanCompletion(CloudLug.COMPLETION_TIMEOUT)

        app.device.openNotification()
        val gone = app.device.wait(Until.gone(NOTIFICATION), SHADE_TIMEOUT)
        app.device.pressBack()
        if (!gone) fail("the transfer completed, and the shade still shows it as running.")
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /** `directionLabel` for the demo pair, as §24.1 and the journey test read it. */
        val NOTIFICATION = By.pkg(SYSTEM_UI).text("Demo provider -> Demo destination")

        const val SHADE_TIMEOUT = 15_000L
    }
}
