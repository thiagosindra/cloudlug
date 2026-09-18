package dev.thiagosindra.cloudlug.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the §24.2 wizard the way a person does, from the home screen to a
 * running transfer.
 *
 * This exists because v0.2.1 shipped a wizard whose picker was empty on a real
 * device while every check was green. `FirstRunSmokeTest` proved the app
 * starts; nothing proved it *works*, and the difference was a transfer that
 * could not be created at all (ADR-0026).
 *
 * It clicks real rows and reads real text rather than reaching for the
 * ViewModel, because the defect lived exactly there — in what the screen was
 * given to draw. Every assertion below would have failed on v0.2.1.
 */
@RunWith(AndroidJUnit4::class)
class NewTransferJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_transfer_can_be_started_from_the_wizard() {
        // Waits before the first touch as well as every later one. The first
        // run of this test failed here, and a bare click cannot say whether the
        // home screen had not composed yet or whether MainActivity was showing
        // a crash report instead — awaitText prints the screen either way.
        node("New Transfer").performClick()

        // 1 and 2: the two accounts. §2.2 forbids transferring to the same
        // provider, so the destination list must still offer the other one.
        awaitText("1. Choose the source account")
        node("Dropbox").performClick()

        awaitText("2. Choose the destination account")
        node("Google Drive").performClick()

        // 3: the step that was empty. The demo tree seeds these three folders,
        // so their absence is the v0.2.1 defect exactly.
        awaitText("3. Choose what to transfer")
        node("docs")
        node("projects")
        node("photos").performClick()
        node("Next").performClick()

        // 4: destination folders, seeded the same way and broken the same way.
        awaitText("4. Choose the destination folder")
        node("My Drive").performClick()
        node("Review").performClick()

        // 5: the manifest is built here, so this is also the first proof that
        // the engine runs end to end inside the app rather than only in JVM
        // tests.
        awaitText("5. Review", timeoutMillis = 60_000)
        node("Start transfer").performClick()

        awaitText("Transfer started.")
    }

    /** Waits for [text] to exist, then returns it for clicking. */
    private fun node(text: String): SemanticsNodeInteraction {
        awaitText(text)
        return compose.onNodeWithText(text)
    }

    /**
     * The engine works off the main thread, so every step is awaited.
     *
     * On timeout it reports every string on screen, on one line. An earlier
     * version printed the whole semantics tree, which was the right idea and
     * the wrong channel: Gradle's console kept the first two lines of the
     * message and dropped the tree, so the run cost a cycle and answered
     * nothing.
     */
    private fun awaitText(text: String, timeoutMillis: Long = 20_000) {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(diagnose(text), timeout)
        }
    }

    private fun diagnose(missing: String): String {
        val onScreen = runCatching { textsOnScreen() }
            .getOrElse { return "Never found \"$missing\", and the screen could not be read: $it" }
        val prefix = if (onScreen.any { it.startsWith(CRASH_SCREEN_TITLE) }) {
            // Not a flaw in this test. FirstRunSmokeTest runs first in the same
            // app data directory, so a crash it recorded is displayed on the
            // next launch — meaning the app threw an uncaught exception, which
            // matters far more than the assertion that tripped over it.
            "THE APP CRASHED EARLIER: MainActivity is showing the crash reporter, " +
                "not the home screen. Fix the exception below, not this test. "
        } else {
            ""
        }
        return prefix + "Never found \"$missing\". On screen: " +
            onScreen.joinToString(" | ") { it.replace('\n', ' ') }.take(3000)
    }

    /** Every string the screen is currently drawing, flattened. */
    private fun textsOnScreen(): List<String> =
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    private companion object {
        const val CRASH_SCREEN_TITLE = "CloudLug crashed last time"
    }
}
