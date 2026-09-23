package dev.thiagosindra.cloudlug.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the harness can do the one thing `:app`'s own tests cannot: kill
 * CloudLug and live to assert what happens next.
 *
 * §31.4's first four scenarios all begin "kill the process". Instrumentation is
 * loaded into the process of the package it targets, so `am force-stop` issued
 * from `:app`'s `androidTest` takes the test down with the app — the run
 * reports a crashed instrumentation, not a passed or failed assertion. That is
 * not a recovery test; it is a test that cannot survive its own first step.
 *
 * This module instruments itself, so CloudLug is just another package. This
 * test asserts only the mechanism, because the mechanism is what was in doubt:
 * the transfer that gets interrupted, and the assertion that it resumes, are
 * v0.5's work and belong beside the scheduler that makes resuming possible.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathHarnessTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun cloudlug_can_be_force_stopped_by_a_test_that_outlives_it() {
        launchCloudLug()
        assertTrue(
            device.wait(Until.hasObject(By.pkg(CLOUDLUG).depth(0)), LAUNCH_TIMEOUT),
            "CloudLug did not come to the foreground; on screen: ${device.currentPackageName}",
        )

        device.executeShellCommand("am force-stop $CLOUDLUG")
        device.waitForIdle()

        // The assertion that matters is that this line runs at all: from inside
        // :app's androidTest, the force-stop above would have ended the run.
        assertFalse(
            runningProcesses().contains(CLOUDLUG),
            "force-stop left a $CLOUDLUG process behind, so the kill this suite depends on did not happen",
        )

        launchCloudLug()
        assertTrue(
            device.wait(Until.hasObject(By.pkg(CLOUDLUG).depth(0)), LAUNCH_TIMEOUT),
            "CloudLug did not restart after being force-stopped",
        )
    }

    private fun launchCloudLug() {
        val intent = checkNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(CLOUDLUG),
        ) {
            "No launch intent for $CLOUDLUG. Either it is not installed, or this harness cannot " +
                "see it — API 30+ needs the <queries> entry in src/androidTest/AndroidManifest.xml."
        }
        instrumentation.targetContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun runningProcesses(): String = device.executeShellCommand("ps -A")

    private companion object {
        const val CLOUDLUG = "dev.thiagosindra.cloudlug"
        const val LAUNCH_TIMEOUT = 30_000L
    }
}
