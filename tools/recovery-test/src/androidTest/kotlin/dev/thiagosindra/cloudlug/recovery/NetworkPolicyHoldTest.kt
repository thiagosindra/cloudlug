package dev.thiagosindra.cloudlug.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Spec §16 and §24.4 on a device: take the allowed network away mid-transfer,
 * and the transfer has to stop, say why, and start again by itself.
 *
 * §32's fifth invariant is that no transfer occurs over a network its policy
 * disallows, and §16 adds that it resumes automatically once an allowed one is
 * back. Both schedulers state the policy as the job's own constraint, so the
 * platform stops the job before the engine gets a say — which is exactly why
 * this cannot be a JVM test. A fake `NetworkMonitor` exercises the engine's
 * gate and nothing about what WorkManager does to a worker whose constraints
 * stopped being met.
 *
 * It is also where §24.4's "waiting states explicitly say why" is decided. A
 * worker's foreground notification dies with the worker, so the honest failure
 * this guards against is not a wrong message but no message at all: the screen
 * off, nothing moving, and a notification that simply vanished.
 *
 * Wi-Fi is toggled rather than the metered flag being set, because §16's
 * question is whether the network is metered and the emulator's remaining
 * network — its simulated radio — answers that on its own. Both "metered only"
 * and "nothing at all" are WAITING_FOR_WIFI by ADR-0010, so which one the
 * emulator lands in does not change the assertion.
 */
@RunWith(AndroidJUnit4::class)
class NetworkPolicyHoldTest {

    private val app = CloudLug()

    @Before
    fun wifiUp() = app.setWifi(true)

    /** Whatever the test did, the next one starts on Wi-Fi. */
    @After
    fun restoreWifi() = app.setWifi(true)

    @Test
    fun an_unmetered_only_transfer_holds_when_wifi_goes_away_saying_why_and_resumes_when_it_returns() {
        app.launch(pacePerChunkMillis = CloudLug.PACE_MS)
        // The wizard's §16 default is "Wi-Fi only", which is what this is about.
        app.startDemoTransfer()
        app.awaitMidFile()

        app.setWifi(false)

        // §24.3 first: the transfer parks, and the screen names the condition
        // rather than showing a bare "Paused".
        app.await(WAITING, timeoutMillis = HOLD_TIMEOUT)

        // §24.4: and so does the notification, which is the only place a user
        // with the screen off will see it. This is the assertion that fails if
        // the notification is simply taken down when the job ends.
        app.device.openNotification()
        assertTrue(
            app.device.wait(Until.hasObject(By.text(WAITING)), SHADE_TIMEOUT),
            "the shade never said why the transfer stopped.\n${app.visibleText()}",
        )
        app.device.pressBack()

        // §16: automatic. Nothing is pressed here; the constraint clears and
        // the platform starts the job again.
        app.setWifi(true)
        app.awaitCleanCompletion(RESUME_TIMEOUT)
    }

    private companion object {
        /** TransferNotifications and §24.3's summary line agree on this string. */
        const val WAITING = "Waiting for Wi-Fi"

        const val HOLD_TIMEOUT = 90_000L
        const val SHADE_TIMEOUT = 15_000L

        /**
         * Wi-Fi coming back, WorkManager's ten-second backoff floor, and then
         * the rest of an eleven-megabyte transfer on a cold emulator.
         */
        const val RESUME_TIMEOUT = 240_000L
    }
}
