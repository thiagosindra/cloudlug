package dev.thiagosindra.cloudlug.app

import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("New Transfer").performClick()

        // 1 and 2: the two accounts. §2.2 forbids transferring to the same
        // provider, so the destination list must still offer the other one.
        awaitText("1. Choose the source account")
        compose.onNodeWithText("Dropbox").performClick()

        awaitText("2. Choose the destination account")
        compose.onNodeWithText("Google Drive").performClick()

        // 3: the step that was empty. The demo tree seeds these three folders,
        // so their absence is the v0.2.1 defect exactly.
        awaitText("3. Choose what to transfer")
        awaitText("photos")
        compose.onNodeWithText("docs").assertIsDisplayed()
        compose.onNodeWithText("projects").assertIsDisplayed()

        compose.onNodeWithText("photos").performClick()
        compose.onNodeWithText("Next").performClick()

        // 4: destination folders, seeded the same way and broken the same way.
        awaitText("4. Choose the destination folder")
        awaitText("My Drive")
        compose.onNodeWithText("My Drive").performClick()
        compose.onNodeWithText("Review").performClick()

        // 5: the manifest is built here, so this is also the first proof that
        // the engine runs end to end inside the app rather than only in JVM
        // tests.
        awaitText("5. Review", timeoutMillis = 60_000)
        compose.onNodeWithText("Start transfer").performClick()

        awaitText("Transfer started.")
    }

    /** The engine works off the main thread, so every step is awaited. */
    private fun awaitText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
