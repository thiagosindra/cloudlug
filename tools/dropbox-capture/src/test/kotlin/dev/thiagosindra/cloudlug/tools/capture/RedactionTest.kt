package dev.thiagosindra.cloudlug.tools.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of the capture tool that can be checked without an account — and
 * the half where a mistake is unrecoverable, because a fixture is committed.
 */
class RedactionTest {

    @Test
    fun `the same object keeps the same pseudonym across bodies`() {
        val redaction = Redaction(ROOT)
        val parent = redaction.redact("""{"entries":[{"id":"id:realA","name":"nested"}]}""")
        val child = redaction.redact("""{"id":"id:realA","name":"nested","size":3}""")

        // Containment is the property the enumeration tests check (ADR-0029).
        // A per-occurrence pseudonym would sever it while leaving every file
        // looking plausible on its own.
        val pseudonym = Regex("\"(id:[^\"]+)\"").find(parent)!!.groupValues[1]
        assertTrue(pseudonym in child, "the same id must redact the same way in every file")
        assertFalse("realA" in parent || "realA" in child)
    }

    @Test
    fun `different objects get different pseudonyms`() {
        val redacted = Redaction(ROOT).redact("""{"a":"id:realA","b":"id:realB"}""")
        val ids = Regex("\"(id:[^\"]+)\"").findAll(redacted).map { it.groupValues[1] }.toList()

        assertEquals(2, ids.toSet().size, "two objects collapsed into one pseudonym: $redacted")
    }

    @Test
    fun `a path under the capture root is rebased, not deleted`() {
        val redacted = Redaction(ROOT).redact("""{"path_lower":"$ROOT/capture-1/nested/x.bin"}""")

        // The shape has to survive: the tests care that a child's path sits
        // under its parent's, not what the account calls the root.
        assertEquals("""{"path_lower":"/fixtures/capture-1/nested/x.bin"}""", redacted)
    }

    @Test
    fun `a path from outside the root is cut down rather than passed through`() {
        val redacted = Redaction(ROOT).redact("""{"path_display":"/Personal/Taxes/2025 return.pdf"}""")

        // This should not arise — the tool only touches what it made — but a
        // redactor whose failure mode is "emit the original" is the wrong way
        // round.
        assertEquals("""{"path_display":"/fixtures/2025 return.pdf"}""", redacted)
        assertFalse("Personal" in redacted || "Taxes" in redacted)
    }

    @Test
    fun `account ids are replaced too`() {
        val redacted = Redaction(ROOT).redact("""{"sharing_info":{"modified_by":"dbid:realAccount"}}""")

        assertFalse("realAccount" in redacted)
        assertTrue("dbid:" in redacted, "the prefix is kept so the shape still parses as an account id")
    }

    @Test
    fun `hashes, revisions, sessions and timestamps are kept verbatim`() {
        val body = """{"rev":"a1c10ce0dd78","content_hash":"$HASH","session_id":"AAAAAA","server_modified":"2026-09-22T15:18:01Z"}"""

        // §21 verifies against the content hash, so a redacted one would leave
        // a fixture that cannot test what it was captured for.
        assertEquals(body, Redaction(ROOT).redact(body))
    }

    @Test
    fun `everything the tool does not target is left byte for byte`() {
        // Spacing and key order carry no meaning to a parser but they are
        // evidence: a fixture is a record of what arrived, and re-serialising
        // it would make it a record of what kotlinx thinks arrived.
        val body = "{ \"name\" : \"captured.bin\" ,\n  \"size\":1024 }"

        assertEquals(body, Redaction(ROOT).redact(body))
    }

    private companion object {
        const val ROOT = "/cloudlug-contract-tests"
        const val HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
