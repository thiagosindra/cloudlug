package dev.thiagosindra.cloudlug.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * §24.5 on a device, up to the point where a real Dropbox sign-in would begin.
 *
 * The Custom Tab itself is not driven here: that would mean a browser, a
 * network and someone's password, none of which belong in a CI run. What this
 * covers is everything around it — that the screen exists, is reachable from
 * §24.1, describes each provider truthfully, and offers exactly the actions
 * ADR-0028 chose.
 */
@RunWith(AndroidJUnit4::class)
class AccountsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_accounts_screen_is_reachable_and_tells_the_truth_about_each_provider() {
        compose.node("Accounts").performClick()

        // Dropbox is the real adapter as of v0.3 and nothing is connected on a
        // fresh install, so it must offer a way in rather than pretending.
        compose.awaitText("Dropbox")
        compose.awaitText("Not connected")
        compose.awaitText("Connect")

        // Google Drive has no connector, so no Connect button — better than a
        // greyed one that invites a press it can never honour, and it leaves
        // exactly one "Connect" on screen to press.
        //
        // In a debug build it does have a row: DemoAccounts seeds one so the
        // wizard has a destination to offer. Saying "not supported yet" beside
        // an account the wizard will happily use would be the lie, so what is
        // asserted here is the demo account, not that sentence.
        compose.awaitText("Google Drive")
        compose.awaitText("demo-destination@example.invalid")

        // The demo provider is not something anyone signs in to, so §24.5
        // leaves it out even though §24.2 offers it in debug.
        assertTrue(
            "the demo provider is listed as something you could sign in to",
            compose.onAllNodesWithText("Demo provider", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )

        // §8.1's promise, stated where the user decides whether to trust it.
        compose.awaitText("never sees your password")
    }

    @Test
    fun connecting_without_a_browser_explains_itself_instead_of_crashing() {
        // §8.1 requires a real browser so CloudLug never sees the user's
        // password, which makes a browser-less device a genuine dead end —
        // but one the user can be told about. Before this, AppAuth threw
        // ActivityNotFoundException straight out of the button's onClick,
        // where nothing catches it.
        assumeTrue(
            "this device has a browser, so the no-browser path cannot be exercised here",
            !hasBrowser(),
        )

        compose.node("Accounts").performClick()
        compose.awaitText("Dropbox")

        compose.node("Connect").performClick()

        compose.awaitText("no browser installed")
    }

    /** Approximates AppAuth's own browser search closely enough to skip on. */
    private fun hasBrowser(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(probe, 0)
            .isNotEmpty()
    }

    @Test
    fun backing_out_of_accounts_returns_to_the_transfer_list() {
        compose.node("Accounts").performClick()
        compose.awaitText("Dropbox")

        compose.node("Back").performClick()

        compose.awaitText("New Transfer")
    }

    @Test
    fun disconnecting_an_account_with_no_connector_does_not_crash() {
        // The demo Drive row has a Disconnect button and no connector behind
        // it. Looking that connector up with error() threw
        // IllegalStateException, which §24.5's error handling does not catch,
        // so the press took the app down.
        compose.node("Accounts").performClick()
        compose.awaitText("demo-destination@example.invalid")

        // "Disconnect…" opens the confirmation; "Disconnect" inside it acts.
        compose.node("Disconnect\u2026").performClick()
        compose.awaitText("will revoke its access")
        compose.node("Disconnect").performClick()

        // The row is gone and the app is still here.
        compose.awaitText("Accounts")
        assertTrue(
            "the demo Drive account survived being disconnected",
            compose.onAllNodesWithText("demo-destination@example.invalid", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
