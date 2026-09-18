package dev.thiagosindra.cloudlug.database.room

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `:core:database` is a Kotlin/JVM module that ships inside an Android APK, so
 * its main source set must name no API that exists on only one of those
 * platforms.
 *
 * This is checked mechanically because the failure mode is invisible at compile
 * time and silent in every JVM test. v0.2 called
 * `Room.databaseBuilder(name, factory)` — the JVM overload — from this module.
 * It compiled, 271 tests passed, CI was green, and the app died on the first
 * real device with:
 *
 * ```
 * NoSuchMethodError: No direct method <init>(Lkotlin/reflect/KClass;
 *   Ljava/lang/String;Lkotlin/jvm/functions/Function0;)V
 *   in class Landroidx/room/RoomDatabase$Builder;
 * ```
 *
 * because the Android artifact's `RoomDatabase.Builder` takes a `Context`. An
 * emulator test now covers this too, but this one runs in milliseconds on every
 * JVM build and names the rule rather than the symptom (ADR-0025).
 *
 * Construction belongs to whoever knows the platform: `:app` via
 * [CloudLugDatabaseFactory], and the JVM tests via `TestDatabases`.
 */
class NoPlatformSpecificRoomApiTest {

    /**
     * Entry points whose overloads differ between `room-runtime-android` and
     * `room-runtime-jvm`, and the driver that only one of them should carry.
     */
    private val forbidden = listOf(
        "Room.databaseBuilder",
        "Room.inMemoryDatabaseBuilder",
        "BundledSQLiteDriver",
        "AndroidSQLiteDriver",
    )

    @Test
    fun `the main source set constructs no database`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "expected to scan this module's sources")

        val offenders = sources.flatMap { file ->
            val code = stripComments(file.readText())
            forbidden.filter { it in code }.map { "${file.path}: names '$it'" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                ":core:database must not construct a Room database — it has no way to know " +
                    "which platform it is on. Move this to :app (CloudLugDatabaseFactory) or to " +
                    "the test source set (TestDatabases). See ADR-0025.\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    /**
     * The bundled driver ships native SQLite for every ABI. As an `api`
     * dependency in v0.2 it went into the APK alongside the platform's own
     * SQLite; the app uses the platform driver now, so this stays test-scoped.
     */
    @Test
    fun `the bundled sqlite driver is test-scoped`() {
        val build = stripComments(File("build.gradle.kts").readText())
        val declarations = build.lines().map { it.trim() }.filter { "sqlite.bundled" in it }

        assertTrue(declarations.isNotEmpty(), "expected the bundled driver to be declared for tests")
        val shipped = declarations.filterNot { it.startsWith("testImplementation") }
        assertTrue(
            shipped.isEmpty(),
            "the bundled SQLite driver must not reach the APK (ADR-0021 as revised): $shipped",
        )
    }

    /** Same contract as `ProviderNeutralityTest`: conservative in one direction only. */
    private fun stripComments(source: String): String {
        val withoutBlocks = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return withoutBlocks.lines().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment >= 0) line.substring(0, comment) else line
        }
    }
}
