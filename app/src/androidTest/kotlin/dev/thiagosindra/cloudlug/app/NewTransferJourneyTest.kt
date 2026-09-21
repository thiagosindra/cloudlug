package dev.thiagosindra.cloudlug.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the §24.2 wizard the way a person does, from the home screen to a
 * completed, verified transfer.
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
        compose.node("New Transfer").performClick()

        // 1 and 2: the two accounts. §2.2 forbids transferring to the same
        // provider, so the destination list must still offer the other one.
        //
        // The source is the demo provider rather than Dropbox as of v0.3:
        // ProviderType.DROPBOX is the real adapter now and would want a
        // connected account and a network. What this test is for is the engine
        // and the §24 screens end to end, which the fake exercises completely
        // (§31.3) and the network would only make flaky.
        compose.awaitText("1. Choose the source account")
        compose.node("Demo provider").performClick()

        compose.awaitText("2. Choose the destination account")
        compose.node("Google Drive").performClick()

        // 3: the step that was empty. The demo tree seeds these three folders,
        // so their absence is the v0.2.1 defect exactly.
        compose.awaitText("3. Choose what to transfer")
        compose.node("docs")
        compose.node("projects")
        compose.node("photos").performClick()
        compose.node("Next").performClick()

        // 4: destination folders, seeded the same way and broken the same way.
        compose.awaitText("4. Choose the destination folder")
        compose.node("My Drive").performClick()
        compose.node("Review").performClick()

        // 5: the manifest is built here, so this is also the first proof that
        // the engine runs end to end inside the app rather than only in JVM
        // tests.
        compose.awaitText("5. Review", timeoutMillis = 60_000)
        compose.node("Start transfer").performClick()

        // Starting replaces the wizard with the transfer's detail screen —
        // MainActivity pops NEW_TRANSFER so that backing out of a started
        // transfer reaches home rather than step 5 — so the wizard's own
        // "Transfer started." is never drawn. §24.3 is where a started
        // transfer is observable.
        compose.awaitText("Demo provider -> Google Drive", timeoutMillis = 30_000)

        // And it does not merely start. The engine runs to completion inside
        // the app, with every item verified at the destination per §21, which
        // until now had only ever been shown in JVM tests.
        compose.awaitText("Completed", timeoutMillis = 60_000)
        compose.awaitText("verified by destination hash")
    }
}
