package dev.thiagosindra.cloudlug.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §26 is a promise about what can never reach a log, so these are written as
 * "this string does not appear" rather than "the output equals this".
 */
class RedactionTest {

    private val token = "sl.B7xKq-notarealtoken-9f2Za"

    @Test
    fun `the authorization header is never loggable`() {
        assertEquals(Redaction.REDACTED, Redaction.header("Authorization", "Bearer $token"))
        assertEquals(Redaction.REDACTED, Redaction.header("authorization", "Bearer $token"))
    }

    @Test
    fun `an unknown header is redacted rather than allowed`() {
        // Deny by default: a header some future adapter adds must not leak
        // because nobody remembered to add it to a block-list.
        assertEquals(Redaction.REDACTED, Redaction.header("X-Some-Future-Token", token))
    }

    @Test
    fun `dropbox api arg is redacted because it carries file paths`() {
        val arg = """{"path":"/Taxes/2025 return.pdf"}"""
        assertEquals(Redaction.REDACTED, Redaction.header("Dropbox-API-Arg", arg))
    }

    @Test
    fun `headers worth reading survive`() {
        assertEquals("application/json", Redaction.header("Content-Type", "application/json"))
        assertEquals("30", Redaction.header("Retry-After", "30"))
    }

    @Test
    fun `a token in a query string is removed but the shape is kept`() {
        val redacted = Redaction.url("https://api.example.com/oauth2/token?code=abc123&client_id=spv3k58wyxixvz4")
        assertFalse("abc123" in redacted, "the authorization code survived: $redacted")
        assertTrue("code=${Redaction.REDACTED}" in redacted)
        // The client id is public and identifies the app, so it stays readable.
        assertTrue("client_id=spv3k58wyxixvz4" in redacted)
    }

    @Test
    fun `a url without a query is untouched`() {
        assertEquals("https://api.example.com/2/files/upload", Redaction.url("https://api.example.com/2/files/upload"))
    }

    @Test
    fun `a token grant body is stripped of its secrets`() {
        val grant = """{"access_token":"$token","refresh_token":"xyz","token_type":"bearer","expires_in":14400}"""
        val redacted = Redaction.body(grant)
        assertFalse(token in redacted, "the access token survived: $redacted")
        assertFalse("xyz" in redacted, "the refresh token survived: $redacted")
        // What is left still says what kind of response this was.
        assertTrue("\"token_type\":\"bearer\"" in redacted)
        assertTrue("\"expires_in\":14400" in redacted)
    }
}
