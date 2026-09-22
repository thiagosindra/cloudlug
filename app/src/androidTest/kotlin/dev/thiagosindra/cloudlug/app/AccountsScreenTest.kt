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

        // Google Drive is v0.4. Saying so, with no button, is better than a
        // greyed Connect that invites a press it can never honour — and it
        // leaves exactly one "Connect" on screen to press.
        compose.awaitText("Google Drive")
        compose.awaitText("Not supported in this version yet")

        // The demo provider has no account, no sign-in and nothing to revoke,
        // so it has no row here even though the wizard offers it in debug.
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
}
