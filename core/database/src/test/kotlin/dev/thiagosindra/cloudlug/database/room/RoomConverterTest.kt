package dev.thiagosindra.cloudlug.database.room

import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A converter is the one place a persistence bug is invisible: the row reads
 * back as *a* valid value, just not the one written. These assert round-trips
 * value by value, including the empty cases.
 */
class RoomConverterTest {

    @Test
    fun `paths round-trip, including the root and non-ASCII segments`() {
        for (path in listOf(
            CloudPath.ROOT,
            CloudPath.parse("a"),
            CloudPath.parse("photos/2026/April/sunset.png"),
            CloudPath.parse("dossiê/relatório final.txt"),
        )) {
            val stored = Converters.pathToString(path)
            assertEquals(path, Converters.stringToPath(stored), "round trip of '$path'")
        }
        assertNull(Converters.pathToString(null))
        assertNull(Converters.stringToPath(null))
    }

    @Test
    fun `hashes round-trip carrying their algorithm`() {
        for (algorithm in HashAlgorithm.entries) {
            val hash = ProviderHash(algorithm, "abc123")
            val stored = Converters.hashToString(hash)
            val back = assertNotNullValue(Converters.stringToHash(stored))
            assertEquals(hash, back)
            assertEquals(algorithm, back.algorithm, "algorithm survived storage")
        }
    }

    /**
     * §19.4 makes two hashes comparable only when their algorithms match, so a
     * stored digest without its algorithm would let a Dropbox content_hash be
     * compared against a Drive MD5. Storing it bare must be impossible to read.
     */
    @Test
    fun `a hash stored without an algorithm is rejected, not guessed`() {
        assertFailsWith<IllegalArgumentException> { Converters.stringToHash("deadbeef") }
        assertFailsWith<IllegalArgumentException> { Converters.stringToHash(":deadbeef") }
        assertFailsWith<IllegalArgumentException> { Converters.stringToHash("sha999:deadbeef") }
    }

    @Test
    fun `checkpoints round-trip verbatim`() {
        val checkpoint = HashCheckpoint("AQAGc2hhMjU2AAAAAAAAAAE=")
        assertEquals(checkpoint, Converters.stringToCheckpoint(Converters.checkpointToString(checkpoint)))
        assertNull(Converters.checkpointToString(null))
    }

    @Test
    fun `instants round-trip at millisecond resolution`() {
        for (instant in listOf(
            Instant.EPOCH,
            Instant.parse("2026-09-17T09:57:00Z"),
            Instant.parse("2026-09-17T09:57:00.123Z"),
        )) {
            assertEquals(instant, Converters.epochMillisToInstant(Converters.instantToEpochMillis(instant)))
        }
        assertNull(Converters.instantToEpochMillis(null))
    }

    @Test
    fun `scope sets round-trip, empty set included`() {
        for (scopes in listOf(
            emptySet(),
            setOf("files.content.read"),
            setOf("files.metadata.read", "files.content.read", "account_info.read"),
        )) {
            assertEquals(scopes, Converters.stringToScopes(Converters.scopesToString(scopes)))
        }
        // An empty set and null are different things: no scopes granted versus
        // no account. They must not collapse into each other.
        assertEquals(emptySet(), Converters.stringToScopes(""))
        assertNull(Converters.stringToScopes(null))
    }

    private fun <T : Any> assertNotNullValue(value: T?): T =
        value ?: throw AssertionError("expected a value, got null")
}
