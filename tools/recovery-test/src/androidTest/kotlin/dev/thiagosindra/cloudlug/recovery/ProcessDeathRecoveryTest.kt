package dev.thiagosindra.cloudlug.recovery

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §31.4, run for real: kill CloudLug in the middle of a file and watch the
 * transfer finish anyway.
 *
 * Instrumentation is loaded into the process of the package it targets, so
 * `am force-stop dev.thiagosindra.cloudlug` issued from `:app`'s `androidTest`
 * takes the test down with the app — the run reports a crashed instrumentation
 * rather than a passed or failed assertion. That is not a recovery test; it is
 * a test that cannot survive its own first step. This module instruments
 * *itself* and drives CloudLug from outside through UiAutomator, so killing
 * CloudLug is an ordinary thing that happens to another package.
 *
 * What it proves is §2.4's claim that nothing may depend on a worker staying
 * alive: the process that was running the transfer is gone, its coroutine with
 * it, and the only thing left is rows in a database. Launching the app again is
 * enough, because `MainActivity` reconciles the schedule from those rows.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /**
     * The mechanism on its own, kept as a separate test because it is the
     * diagnostic for the one below: if both fail, this says whether the kill or
     * the recovery was at fault, which a single test cannot.
     */
    @Test
    fun cloudlug_can_be_force_stopped_by_a_test_that_outlives_it() {
        launchCloudLug()
        awaitCloudLugForeground()

        forceStopCloudLug()

        // The assertion that matters is that this line runs at all.
        launchCloudLug()
        awaitCloudLugForeground()
    }

    /**
     * §31.4's first scenario, end to end.
     *
     * The transfer is a real one through the demo providers — the same wizard
     * `NewTransferJourneyTest` drives — slowed to a crawl so that the kill
     * lands mid-file rather than after the last byte. Without the pace, the
     * whole demo tree moves in about a second and a force-stop timed off what
     * is on screen would arrive at a finished transfer, which would pass while
     * proving nothing.
     */
    @Test
    fun a_transfer_interrupted_by_process_death_resumes_and_completes() {
        launchCloudLug(pacePerChunkMillis = PACE_MS)
        awaitCloudLugForeground()

        click("New Transfer")

        await("1. Choose the source account")
        click("demo-source@example.invalid")

        await("2. Choose the destination account")
        click("demo-destination@example.invalid")

        // `photos` is the biggest of the demo roots — eleven megabytes across
        // seven files and five folders — which is what gives the kill somewhere
        // to land. The checkbox, not the row: a folder row descends into it.
        await("3. Choose what to transfer")
        clickDescription("Select photos")
        click("Next")

        await("4. Choose the destination folder")
        click("My Drive")
        click("Choose this folder")
        click("Review")

        // Building the manifest walks the whole tree, and the emulator is slow.
        await("5. Review", timeoutMillis = MANIFEST_TIMEOUT)
        click("Start transfer")

        // §24.3 draws CURRENT FILE only while an item is actually moving bytes,
        // so seeing it is the proof that the kill below is mid-file and not
        // before the first byte or after the last.
        await("CURRENT FILE", timeoutMillis = START_TIMEOUT)

        // And §24.3's own file count says how much of the manifest is still to
        // come, which is what makes "it resumed" mean something afterwards. A
        // transfer that was already on its last file would recover trivially.
        val counted = checkNotNull(device.findObject(By.text(FILE_COUNT))?.text) {
            "§24.3 showed CURRENT FILE but no file count.\n${visibleText()}"
        }
        val (movedBefore, total) = counted.removeSuffix(" files").split(" / ").map(String::toInt)
        assertTrue(
            movedBefore < total,
            "all $total items were already moved before the kill landed; raise PACE_MS",
        )

        forceStopCloudLug()

        // Relaunched at full speed: the point is that it resumes, not that
        // resuming is slow too.
        launchCloudLug()
        awaitCloudLugForeground()

        // Either screen will do. After a force-stop the task is usually gone
        // and the app opens on §24.1, whose row carries the same summary line
        // as §24.3's; if the task survived, the detail screen is already there
        // and updating. Asserting on the text rather than on which screen shows
        // it keeps the test out of that platform difference.
        val completed = device.wait(
            Until.findObject(By.text(Pattern.compile("Completed\\b.*"))),
            COMPLETION_TIMEOUT,
        )
        assertTrue(
            completed != null,
            "the interrupted transfer did not complete after CloudLug was restarted.\n${visibleText()}",
        )

        // "Completed with issues" also begins with "Completed", and it is
        // exactly what a half-resumed transfer would say: §32.1 requires the
        // files that were mid-flight to be finished, not abandoned as failed.
        assertNull(
            device.findObject(By.text(Pattern.compile("(?i).*\\b(failed|issues|conflict)\\b.*"))),
            "the transfer finished, but not cleanly.\n${visibleText()}",
        )
    }

    // ------------------------------------------------------------- the harness

    private fun launchCloudLug(pacePerChunkMillis: Long = 0L) {
        val intent = checkNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(CLOUDLUG),
        ) {
            "No launch intent for $CLOUDLUG. Either it is not installed, or this harness cannot " +
                "see it — API 30+ needs the <queries> entry in src/main/AndroidManifest.xml, " +
                "which is this application's manifest and not the test APK's."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pacePerChunkMillis > 0L) intent.putExtra(EXTRA_DEMO_PACE_MS, pacePerChunkMillis)
        instrumentation.targetContext.startActivity(intent)
    }

    private fun awaitCloudLugForeground() = assertTrue(
        device.wait(Until.hasObject(By.pkg(CLOUDLUG).depth(0)), LAUNCH_TIMEOUT),
        "CloudLug did not come to the foreground; on screen: ${device.currentPackageName}",
    )

    private fun forceStopCloudLug() {
        device.executeShellCommand("am force-stop $CLOUDLUG")
        device.waitForIdle()

        // `pidof`, not a substring of `ps`. This harness's own package is
        // `…cloudlug.recovery`, which *contains* `…cloudlug`, so scanning the
        // process list for the name found the harness itself and reported that
        // CloudLug had survived being killed. `pidof` takes the exact name.
        assertEquals(
            "",
            device.executeShellCommand("pidof $CLOUDLUG").trim(),
            "force-stop left a $CLOUDLUG process behind, so the kill this suite depends on did not happen",
        )
    }

    private fun await(text: String, timeoutMillis: Long = STEP_TIMEOUT) = awaitSelector(
        By.text(text),
        timeoutMillis,
    ) { "waited ${timeoutMillis}ms for \"$text\"" }

    private fun click(text: String) =
        awaitSelector(By.text(text), STEP_TIMEOUT) { "waited ${STEP_TIMEOUT}ms to click \"$text\"" }.click()

    private fun clickDescription(description: String) = awaitSelector(By.desc(description), STEP_TIMEOUT) {
        "waited ${STEP_TIMEOUT}ms to click the control described \"$description\""
    }.click()

    private fun awaitSelector(selector: BySelector, timeoutMillis: Long, what: () -> String) =
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
    private fun visibleText(): String {
        val texts = device.findObjects(By.pkg(CLOUDLUG))
            .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription }
            .distinct()
        return "On screen (${device.currentPackageName}): " +
            if (texts.isEmpty()) "nothing from $CLOUDLUG" else texts.joinToString(" | ")
    }

    private companion object {
        const val CLOUDLUG = "dev.thiagosindra.cloudlug"

        /** Named by MainActivity too; see the comment on its constant. */
        const val EXTRA_DEMO_PACE_MS = "dev.thiagosindra.cloudlug.DEMO_PACE_MS"

        /**
         * Per chunk, on both sides. The demo tree is eight chunks and about
         * fifteen source reads, so this buys roughly fifteen seconds of
         * transfer — long enough that the kill is unambiguously mid-file and
         * short enough that the test is not mostly sleeping.
         */
        const val PACE_MS = 700L

        /** §24.3's "3 / 12 files". */
        val FILE_COUNT: Pattern = Pattern.compile("\\d+ / \\d+ files")

        const val LAUNCH_TIMEOUT = 30_000L
        const val STEP_TIMEOUT = 15_000L
        const val MANIFEST_TIMEOUT = 60_000L
        const val START_TIMEOUT = 60_000L
        const val COMPLETION_TIMEOUT = 180_000L
    }
}
