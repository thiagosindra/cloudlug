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
        // As of v0.4 these really are accounts, not providers: §24.2 offers
        // the rows in §12.4, which for the demo providers are seeded by
        // DemoAccounts in debug builds.
        //
        // The source is the demo provider rather than Dropbox as of v0.3:
        // ProviderType.DROPBOX is the real adapter now and would want a
        // connected account and a network. What this test is for is the engine
        // and the §24 screens end to end, which the fake exercises completely
        // (§31.3) and the network would only make flaky.
        compose.awaitText("1. Choose the source account")
        compose.node("demo-source@example.invalid").performClick()

        compose.awaitText("2. Choose the destination account")
        compose.node("demo-destination@example.invalid").performClick()

        // 3: the step that was empty. The demo tree seeds these three folders,
        // so their absence is the v0.2.1 defect exactly.
        compose.awaitText("3. Choose what to transfer")
        compose.node("docs")
        compose.node("projects")
        // The checkbox, not the row: a folder row opens the folder now, and
        // this journey wants all of `photos` rather than a walk inside it.
        compose.labelled("Select photos").performClick()
        compose.node("Next").performClick()

        // 4: destination folders, seeded the same way and broken the same way.
        // The row opens a folder; the button takes the level you are standing
        // on, so choosing "My Drive" is two actions and neither is ambiguous.
        compose.awaitText("4. Choose the destination folder")
        compose.node("My Drive").performClick()
        compose.node("Choose this folder").performClick()
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

    /**
     * §9's browser: descend, select across levels, come back, and see what was
     * picked.
     *
     * Every row used to toggle selection and nothing opened a folder, so only
     * an account's top level could be transferred at all — the tree below it
     * was unreachable, and `photos` could be taken whole or not at all. That is
     * what this walks.
     *
     * It also pins the part that is easy to get wrong once descent exists: a
     * selection made two levels down has to survive coming back up, and it has
     * to remember *where* it was made. `CloudObject` carries identity, not a
     * path (§6), so the display paths on the review step can only be right if
     * the browser recorded them at the moment of picking.
     */
    @Test
    fun the_picker_descends_and_keeps_what_was_chosen_along_the_way() {
        compose.node("New Transfer").performClick()

        compose.awaitText("1. Choose the source account")
        compose.node("demo-source@example.invalid").performClick()
        compose.awaitText("2. Choose the destination account")
        compose.node("demo-destination@example.invalid").performClick()

        // Down one: `photos` holds the two year folders and nothing else.
        compose.awaitText("3. Choose what to transfer")
        compose.node("photos").performClick()
        compose.awaitText("2026")

        // Down two: the year holds two files and a month.
        compose.node("2025").performClick()
        compose.awaitText("July")

        // One file and one folder, at this level.
        compose.labelled("Select photo1.png").performClick()
        compose.labelled("Select July").performClick()
        compose.awaitText("2 selected")

        // Back up twice, to where we started.
        compose.node("Up").performClick()
        compose.awaitText("2026")
        compose.node("Up").performClick()
        compose.awaitText("projects")

        // Still two. A selection that evaporated on the way out would make
        // picking from two different folders impossible, which is most of the
        // reason to have a browser at all.
        compose.awaitText("2 selected")
        compose.node("Next").performClick()

        compose.awaitText("4. Choose the destination folder")
        compose.node("My Drive").performClick()
        compose.node("Choose this folder").performClick()
        compose.awaitText("Into /My Drive")
        compose.node("Review").performClick()

        // §10 reproduces the source's own ancestors under the enclosing
        // folder, so both roots carry the walk that found them — not the bare
        // names a flat picker would have produced.
        compose.awaitText("5. Review", timeoutMillis = 60_000)
        compose.awaitText("photos/2025/photo1.png")
        compose.awaitText("photos/2025/July")
    }
}
