package dev.thiagosindra.cloudlug.app

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the app the way a phone does, on an emulator.
 *
 * This exists because v0.2 passed 271 JVM tests, built a green APK, and then
 * died on the first real device: `:core:database` named Room's JVM-only
 * builder, which compiles everywhere and resolves nowhere on Android
 * (ADR-0025). No test that ran off-device could have caught it, because the
 * defect *is* the difference between the two platforms.
 *
 * The test deliberately does nothing clever. It starts `MainActivity` against
 * the real `CloudLugApplication`, so Hilt builds the real graph and Room opens
 * the real database, and then asserts the app is still alive. Anything that
 * throws while constructing that graph fails here rather than on a phone.
 *
 * `@HiltAndroidTest` is not used on purpose: swapping in test bindings would
 * replace the very objects whose real construction is under test.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunSmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun the_app_launches_and_stays_up() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(
                "MainActivity did not reach RESUMED — the Hilt graph or a screen threw",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    /**
     * Proves the database was really opened rather than merely injected.
     *
     * Room creates the file lazily on the first query, and the home screen's
     * ViewModel observes the transfer list as soon as it composes, so the file
     * existing is evidence that a query reached SQLite on the device. This is
     * the exact step that threw `NoSuchMethodError` in v0.2.
     */
    @Test
    fun launching_opens_the_room_database_on_device() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val database = context.getDatabasePath("cloudlug.db")
            waitUntil("Room never created ${database.absolutePath}") { database.isFile }
            assertTrue("the database file is empty", database.length() > 0)
        }
    }

    /** Polls rather than sleeps: the first query happens off the main thread. */
    private fun waitUntil(message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
