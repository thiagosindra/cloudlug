package dev.thiagosindra.cloudlug.recovery

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CloudLug, driven from outside its own process.
 *
 * Instrumentation is loaded into the process of the package it targets, so a
 * test inside `:app` cannot force-stop CloudLug — it would take itself down
 * with it — and cannot see a notification the app posted, because the shade
 * belongs to the system UI. This module instruments *itself*, which makes both
 * ordinary: CloudLug is just another package, and everything here goes through
 * UiAutomator.
 *
 * The cost is that nothing can be reached directly. There is no ViewModel to
 * ask and no database to open; a step happened when the screen says it did.
 * That is also the point — these are the §31.4 scenarios, and a scenario that
 * reads its own answer out of the process it just killed is not testing
 * recovery.
 */
class CloudLug {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    /**
     * @param pacePerChunkMillis how long the demo providers spend on each
     *   chunk. Zero — the default, and every launch but a test's — leaves them
     *   instant. See `DemoPace`.
     */
    fun launch(pacePerChunkMillis: Long = 0L) {
        val intent = checkNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE),
        ) {
            "No launch intent for $PACKAGE. Either it is not installed, or this harness cannot " +
                "see it — API 30+ needs the <queries> entry in src/main/AndroidManifest.xml, " +
                "which is this application's manifest and not the test APK's."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pacePerChunkMillis > 0L) intent.putExtra(EXTRA_DEMO_PACE_MS, pacePerChunkMillis)
        instrumentation.targetContext.startActivity(intent)

        assertTrue(
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT),
            "CloudLug did not come to the foreground; on screen: ${device.currentPackageName}",
        )
    }

    fun forceStop() {
        device.executeShellCommand("am force-stop $PACKAGE")
        device.waitForIdle()

        // `pidof`, not a substring of `ps`. This harness's own package is
        // `…cloudlug.recovery`, which *contains* `…cloudlug`, so scanning the
        // process list for the name found the harness itself and reported that
        // CloudLug had survived being killed. `pidof` takes the exact name.
        assertEquals(
            "",
            device.executeShellCommand("pidof $PACKAGE").trim(),
            "force-stop left a $PACKAGE process behind, so the kill this suite depends on did not happen",
        )
    }

    /**
     * The §24.2 wizard, from the home screen to a transfer that is moving
     * bytes, on the demo providers `NewTransferJourneyTest` uses.
     *
     * `photos` is the biggest of the demo roots — eleven megabytes across seven
     * files and five folders — which is what gives an interruption somewhere to
     * land. The checkbox, not the row: a folder row descends into it.
     */
    fun startDemoTransfer() {
        click("New Transfer")

        await("1. Choose the source account")
        click("demo-source@example.invalid")

        await("2. Choose the destination account")
        click("demo-destination@example.invalid")

        await("3. Choose what to transfer")
        clickDescription("Select photos")
        click("Next")

        await("4. Choose the destination folder")
        click("My Drive")
        click("Choose this folder")
        // §16's default, and the one the network test needs: the wizard offers
        // "Wi-Fi only" selected.
        click("Review")

        // Building the manifest walks the whole tree, and the emulator is slow.
        await("5. Review", timeoutMillis = MANIFEST_TIMEOUT)
        click("Start transfer")
    }

    /**
     * Waits until §24.3 says a file is actually moving, and returns how much of
     * the manifest is still to come.
     *
     * CURRENT FILE is drawn only while an item is moving bytes, so seeing it is
     * the proof that whatever happens next happens mid-file and not before the
     * first byte or after the last.
     */
    fun awaitMidFile(): Pair<Int, Int> {
        await("CURRENT FILE", timeoutMillis = START_TIMEOUT)
        val counted = checkNotNull(device.findObject(By.text(FILE_COUNT))?.text) {
            "§24.3 showed CURRENT FILE but no file count.\n${visibleText()}"
        }
        val (moved, total) = counted.removeSuffix(" files").split(" / ").map(String::toInt)
        assertTrue(moved < total, "all $total items had already moved; raise the pace")
        return moved to total
    }

    /** Waits for a transfer that finished with nothing to report (§24.1, §24.3). */
    fun awaitCleanCompletion(timeoutMillis: Long) {
        // Either screen will do. §24.1's row carries the same summary line as
        // §24.3's, and which one is showing depends on whether the task
        // survived — a platform difference this has no business asserting on.
        assertTrue(
            device.wait(Until.hasObject(By.text(Pattern.compile("Completed\\b.*"))), timeoutMillis),
            "the transfer never completed.\n${visibleText()}",
        )
        // "Completed with issues" also begins with "Completed", and it is
        // exactly what a half-recovered transfer would say: §32.1 requires the
        // files that were in flight to be finished, not abandoned as failed.
        assertTrue(
            device.findObject(By.text(Pattern.compile("(?i).*\\b(failed|issues|conflict)\\b.*"))) == null,
            "the transfer finished, but not cleanly.\n${visibleText()}",
        )
    }

    /** True once the emulator's Wi-Fi state has settled the way it was asked to. */
    fun setWifi(enabled: Boolean) {
        device.executeShellCommand("svc wifi ${if (enabled) "enable" else "disable"}")
        device.waitForIdle()
    }

    fun await(text: String, timeoutMillis: Long = STEP_TIMEOUT): UiObject2 =
        awaitSelector(By.text(text), timeoutMillis) { "waited ${timeoutMillis}ms for \"$text\"" }

    fun click(text: String) {
        awaitSelector(By.text(text), STEP_TIMEOUT) { "waited ${STEP_TIMEOUT}ms to click \"$text\"" }.click()
    }

    fun clickDescription(description: String) {
        awaitSelector(By.desc(description), STEP_TIMEOUT) {
            "waited ${STEP_TIMEOUT}ms to click the control described \"$description\""
        }.click()
    }

    private fun awaitSelector(selector: BySelector, timeoutMillis: Long, what: () -> String): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMillis)) {
            "${what()}, and it never appeared.\n${visibleText()}"
        }

    /**
     * What is on screen, for the failure message.
     *
     * A UiAutomator failure otherwise says only that a selector found nothing,
     * which cannot distinguish "the button is not there" from "the app is
     * showing a crash report" from "the emulator is on the launcher".
     */
    fun visibleText(): String {
        val texts = device.findObjects(By.pkg(PACKAGE))
            .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription }
            .distinct()
        return "On screen (${device.currentPackageName}): " +
            if (texts.isEmpty()) "nothing from $PACKAGE" else texts.joinToString(" | ")
    }

    companion object {
        const val PACKAGE = "dev.thiagosindra.cloudlug"

        /** Named by MainActivity too; see the comment on its constant. */
        const val EXTRA_DEMO_PACE_MS = "dev.thiagosindra.cloudlug.DEMO_PACE_MS"

        /**
         * Per chunk, on both sides. The demo tree is eight chunks and about
         * fifteen source reads, so this buys roughly fifteen seconds of
         * transfer — long enough that an interruption is unambiguously
         * mid-file, short enough that the test is not mostly sleeping.
         */
        const val PACE_MS = 700L

        const val LAUNCH_TIMEOUT = 30_000L
        const val STEP_TIMEOUT = 15_000L
        const val MANIFEST_TIMEOUT = 60_000L
        const val START_TIMEOUT = 60_000L
        const val COMPLETION_TIMEOUT = 180_000L

        /** §24.3's "3 / 12 files". */
        val FILE_COUNT: Pattern = Pattern.compile("\\d+ / \\d+ files")
    }
}
