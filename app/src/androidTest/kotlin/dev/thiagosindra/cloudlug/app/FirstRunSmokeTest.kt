package dev.thiagosindra.cloudlug.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
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
     * The assertion is Room's own schema, read back from the file on the
     * device, because that is the thing v0.2 could not do: `NoSuchMethodError`
     * was thrown while constructing the builder, so no connection was ever
     * opened and no table was ever created.
     *
     * It deliberately does not assert the file's size. Room journals in WAL
     * mode on Android, so a freshly created schema lives in `cloudlug.db-wal`
     * and the main file stays zero bytes until a checkpoint — an earlier
     * version of this test asserted `length() > 0` and failed on a perfectly
     * healthy app.
     */
    @Test
    fun launching_opens_the_room_database_on_device() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val database = context.getDatabasePath(DATABASE_NAME)
            waitUntil({ "Room never created ${database.absolutePath}" }) { database.isFile }
            waitUntil({ "opened $DATABASE_NAME, but ${EXPECTED_TABLES - tablesIn(database)} never appeared" }) {
                tablesIn(database).containsAll(EXPECTED_TABLES)
            }
        }
    }

    /** Reads the schema back through a second connection, as any client would. */
    private fun tablesIn(database: File): Set<String> = runCatching {
        SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { connection ->
            connection.rawQuery(TABLE_QUERY, null).use { row ->
                buildSet { while (row.moveToNext()) add(row.getString(0)) }
            }
        }
    }.getOrElse { emptySet() }

    /** Polls rather than sleeps: the first query happens off the main thread. */
    private fun waitUntil(message: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(message())
    }

    private companion object {
        const val DATABASE_NAME = "cloudlug.db"
        const val POLL_INTERVAL_MILLIS = 100L
        const val TIMEOUT_MILLIS = 30_000L
        const val TABLE_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table'"

        /** Every entity in `CloudLugRoomDatabase`; see core/database/schemas/…/1.json. */
        val EXPECTED_TABLES = setOf("transfers", "transfer_items", "cache_chunks", "accounts")
    }
}
