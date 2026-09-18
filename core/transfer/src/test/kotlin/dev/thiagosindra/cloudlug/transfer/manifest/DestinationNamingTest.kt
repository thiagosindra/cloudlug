package dev.thiagosindra.cloudlug.transfer.manifest

import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestinationNamingTest {

    private val capabilities = FakeCloudProvider.defaultCapabilities(
        illegalNameCharacters = setOf(':', '/', '\\', '<', '>', '"', '|', '?', '*'),
        maxNameLength = 255,
    )

    @Test
    fun `the enclosing folder is named as spec 10 shows`() {
        assertEquals(
            "CloudLug - 2026-09-17 09-57",
            EnclosingFolderNamer.nameFor(Instant.parse("2026-09-17T09:57:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `the enclosing folder name uses only universally legal characters`() {
        val name = EnclosingFolderNamer.nameFor(Instant.parse("2026-09-17T09:57:00Z"), ZoneOffset.UTC)
        assertTrue(EnclosingFolderNamer.UNIVERSALLY_ILLEGAL.none { it in name })
        assertNull(DestinationNameLegality.check(name, capabilities))
    }

    @Test
    fun `two transfers in the same minute do not merge into one folder`() {
        val base = "CloudLug - 2026-09-17 09-57"
        assertEquals(base, EnclosingFolderNamer.disambiguate(base, taken = emptySet()))
        assertEquals("$base (2)", EnclosingFolderNamer.disambiguate(base, taken = setOf(base)))
        assertEquals("$base (3)", EnclosingFolderNamer.disambiguate(base, taken = setOf(base, "$base (2)")))
    }

    @Test
    fun `legal names pass`() {
        listOf("report.txt", "photo 1.png", "ünïcode.txt", "a".repeat(255)).forEach {
            assertNull(DestinationNameLegality.check(it, capabilities), "'$it' should be legal")
        }
    }

    @Test
    fun `illegal characters and over-long names are conflicts`() {
        listOf("a:b.txt", "a/b.txt", "a?b.txt", "a".repeat(256), "", "   ", ".", "..").forEach {
            assertEquals(
                ItemStatusReason.CONFLICT_ILLEGAL_NAME,
                DestinationNameLegality.check(it, capabilities),
                "'$it' should be rejected",
            )
        }
    }
}
