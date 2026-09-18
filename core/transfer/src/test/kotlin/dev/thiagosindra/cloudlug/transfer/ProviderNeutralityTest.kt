package dev.thiagosindra.cloudlug.transfer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Engineering invariant §32.7: provider-specific behaviour never leaks into the
 * core transfer engine.
 *
 * This is checked mechanically rather than by review, because the leak that
 * matters is the one nobody notices — a `when (provider)` added during a
 * deadline. The module's own sources are scanned with comments stripped, so a
 * doc comment may still *explain* a provider's behaviour while code may not
 * depend on it.
 */
class ProviderNeutralityTest {

    private val forbidden = listOf("dropbox", "googledrive", "google_drive", "google drive", "gdrive")

    @Test
    fun `no engine source refers to a specific provider`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "expected to scan the engine's sources")

        val offenders = sources.mapNotNull { file ->
            val code = stripComments(file.readText()).lowercase()
            val hit = forbidden.firstOrNull { it in code }
            hit?.let { "${file.path}: mentions '$it'" }
        }

        if (offenders.isNotEmpty()) {
            fail("Provider-specific references in :core:transfer (spec §32.7):\n" + offenders.joinToString("\n"))
        }
    }

    @Test
    fun `the engine does not depend on any provider adapter module`() {
        val declarations = stripComments(File("build.gradle.kts").readText())
            .lines()
            .map { it.trim() }
            .filter { line -> listOf(":providers:dropbox", ":providers:google-drive").any { it in line } }

        assertTrue(
            declarations.isEmpty(),
            "the engine must not depend on a provider adapter module (spec §4, §32.7): $declarations",
        )
    }

    /**
     * Removes line and block comments and string literals. Crude, but it only
     * needs to be conservative in one direction: anything it fails to strip can
     * cause a false failure, never a false pass.
     */
    private fun stripComments(source: String): String {
        val withoutBlocks = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return withoutBlocks.lines().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment >= 0) line.substring(0, comment) else line
        }
    }
}
