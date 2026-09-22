package dev.thiagosindra.cloudlug.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thiagosindra.cloudlug.app.crash.crashReporter
import dev.thiagosindra.cloudlug.auth.PendingAuthorization
import dev.thiagosindra.cloudlug.provider.dropbox.DropboxOAuth
import dev.thiagosindra.cloudlug.security.KeystoreSecretStore
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The leg of §8.1 that no test had ever touched: coming back from the browser.
 *
 * v0.3 shipped green on every check and crashed on a real phone the moment the
 * Dropbox consent screen handed control back:
 *
 *   java.lang.RuntimeException: Unable to start activity ComponentInfo{
 *     dev.thiagosindra.cloudlug/net.openid.appauth.RedirectUriReceiverActivity}
 *   Caused by: java.lang.IllegalStateException: You need to use a
 *     Theme.AppCompat theme (or descendant) with this activity.
 *
 * AppAuth's receiver is an AppCompatActivity and inherited `Theme.CloudLug`,
 * which descends from the platform's Material theme. The crash happened in
 * `onCreate`, before the redirect was read — so nothing about the code that
 * handles a redirect was wrong, and no amount of testing that code would have
 * found it. Only starting the activity does.
 *
 * ### What this cannot cover
 *
 * The browser. §8.1 requires a real one, CI has none, and a person's password
 * does not belong in an emulator. So the redirect is delivered directly to the
 * receiver, as the browser would deliver it, and what is asserted is that the
 * activity starts, finishes and leaves the app standing.
 *
 * AppAuth completes the bounce safely with no request of its own in flight: its
 * management activity finds no stored state, logs "No stored state - unable to
 * handle response" and finishes. That is why this test can deliver a redirect
 * without first driving a real authorization.
 */
@RunWith(AndroidJUnit4::class)
class OAuthRedirectTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val pending get() = PendingAuthorization(KeystoreSecretStore(context))

    @After
    fun tearDown() {
        // §8.3: a verifier this test invented has no further use.
        pending.discard()
    }

    @Test
    fun the_redirect_receiver_starts_and_leaves_the_app_standing() {
        // A real pending attempt, so the redirect is shaped the way production's
        // is rather than being a bare intent.
        val challenge = DropboxOAuth.newChallenge()
        pending.remember(challenge)

        // Anything already recorded belongs to an earlier test; clear it so the
        // assertion below is about this bounce.
        crashReporter(context).consumePendingReport()

        val monitor = instrumentation.addMonitor(RECEIVER, null, false)
        context.startActivity(redirect(code = "not-a-real-code", state = challenge.state))

        val receiver = monitor.waitForActivityWithTimeout(15_000)
        instrumentation.removeMonitor(monitor)

        // Null means the activity never reached the point of existing, which is
        // exactly what the theme crash did: it threw inside performLaunchActivity.
        assertNotNull(
            "AppAuth's redirect receiver never started. This is the v0.3 crash if the " +
                "cause is \"You need to use a Theme.AppCompat theme\" — check that :app's " +
                "manifest still overrides the theme for $RECEIVER.",
            receiver,
        )

        // The receiver hands the redirect on and finishes, so it is gone by now.
        // What matters is that the app is not.
        assertNull(
            "the redirect crashed the app; the recorded report follows",
            crashReporter(context).consumePendingReport(),
        )
    }

    @Test
    fun a_redirect_with_no_attempt_in_progress_is_survivable() {
        // What an old link, a duplicated tab, or a reinstall mid-flow produces.
        // §8.4 will refuse to spend the code; the point here is that refusing
        // happens in code rather than in a process death.
        pending.discard()
        crashReporter(context).consumePendingReport()

        val monitor = instrumentation.addMonitor(RECEIVER, null, false)
        context.startActivity(redirect(code = "not-a-real-code", state = "not-our-state"))

        val receiver = monitor.waitForActivityWithTimeout(15_000)
        instrumentation.removeMonitor(monitor)

        assertNotNull("AppAuth's redirect receiver never started", receiver)
        assertNull(
            "an unexpected redirect crashed the app; the recorded report follows",
            crashReporter(context).consumePendingReport(),
        )
    }

    private fun redirect(code: String, state: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$REDIRECT_URI?code=$code&state=$state"))
            .setClassName(context, RECEIVER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val RECEIVER = "net.openid.appauth.RedirectUriReceiverActivity"

        /** Registered with Dropbox and claimed by this app's manifest (§8.1). */
        const val REDIRECT_URI = "dev.thiagosindra.cloudlug://oauth/dropbox"
    }
}
