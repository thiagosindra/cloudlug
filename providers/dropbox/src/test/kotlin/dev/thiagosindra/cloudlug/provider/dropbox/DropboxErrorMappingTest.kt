package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The §23 mapping, checked against a body the service actually sent where one
 * exists and against documented shapes where one does not.
 *
 * §36 asks for this early because the documentation and the wire disagree often
 * enough to matter. The `insufficient_space` case is why: its first fixture was
 * reconstructed from a description and nested the write failure under an inner
 * `path` object, which is not what Dropbox sends — it flattens the member's
 * fields alongside the tag.
 *
 * Seeing the real body is what made the mapper search for a tag at any depth
 * instead of walking `error.path.reason`, which is the obvious thing to write
 * against the reconstruction and would have missed the reason entirely,
 * returning a generic failure where §23 requires a hold. The test below pins
 * that tolerance down so it survives someone later "tidying" the traversal.
 */
class DropboxErrorMappingTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/errors/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    @Test
    fun `a real insufficient_space body becomes a storage hold, not a failure`() {
        val body = fixture("upload_insufficient_space_409.json")

        val error = DropboxErrors.toException(status = 409, body = body)

        // §23: "WAITING_FOR_STORAGE-style hold with user message; not retried
        // automatically." Not PERMANENT, which would fail the item instead of
        // letting the user free space and resume.
        assertEquals(CloudErrorKind.DESTINATION_STORAGE_FULL, error.kind)
        assertFalse(error.kind.isRetryable, "a storage hold must not be retried on a timer")
        assertEquals("path/insufficient_space/", error.code)
    }

    @Test
    fun `the upload session id in that body never reaches the exception`() {
        val body = fixture("upload_insufficient_space_409.json")
        assertTrue("pid_upload_session:" in body, "the fixture no longer carries a session id")

        val error = DropboxErrors.toException(status = 409, body = body)

        val rendered = "${error.message} ${error.code}"
        assertFalse("pid_upload_session" in rendered, "the session id leaked into the exception: $rendered")
    }

    @Test
    fun `the reason is found whether the union is flattened or nested`() {
        // Left: what Dropbox actually sends. Right: the shape the first,
        // reconstructed fixture had. Both must map the same, because the
        // mapping is about the reason, not about where a route chose to put it.
        val flattened = """{"error":{".tag":"path","reason":{".tag":"insufficient_space"}},"error_summary":"path/insufficient_space/"}"""
        val nested = """{"error":{".tag":"path","path":{"reason":{".tag":"insufficient_space"}}},"error_summary":"path/insufficient_space/"}"""

        assertEquals(
            CloudErrorKind.DESTINATION_STORAGE_FULL,
            DropboxErrors.toException(409, flattened).kind,
        )
        assertEquals(
            CloudErrorKind.DESTINATION_STORAGE_FULL,
            DropboxErrors.toException(409, nested).kind,
        )
    }

    @Test
    fun `401 is auth required and is never retryable`() {
        val error = DropboxErrors.toException(
            status = 401,
            body = """{"error_summary":"expired_access_token/","error":{".tag":"expired_access_token"}}""",
        )
        assertEquals(CloudErrorKind.AUTH_REQUIRED, error.kind)
        assertFalse(error.kind.isRetryable, "§23: AUTH_REQUIRED never loops")
    }

    @Test
    fun `a refresh returning invalid_grant is auth required`() {
        val error = DropboxErrors.toException(status = 400, body = """{"error":"invalid_grant"}""")
        assertEquals(CloudErrorKind.AUTH_REQUIRED, error.kind)
    }

    @Test
    fun `429 is throttled and carries the provider's own delay`() {
        val error = DropboxErrors.toException(
            status = 429,
            body = """{"error_summary":"too_many_requests/","error":{".tag":"too_many_requests"}}""",
            retryAfter = 7.seconds,
        )
        assertEquals(CloudErrorKind.THROTTLED, error.kind)
        assertTrue(error.kind.isRetryable)
        assertEquals(7.seconds, error.retryAfter)
    }

    @Test
    fun `429 without a Retry-After still backs off rather than hammering`() {
        val error = DropboxErrors.toException(status = 429, body = "")
        assertEquals(CloudErrorKind.THROTTLED, error.kind)
        assertNotNull(error.retryAfter, "a throttle with no Retry-After must still wait")
    }

    @Test
    fun `5xx and 408 are transient`() {
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, DropboxErrors.toException(503, "").kind)
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, DropboxErrors.toException(500, "").kind)
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, DropboxErrors.toException(408, "").kind)
    }

    @Test
    fun `a lost upload session is restartable, not fatal`() {
        val error = DropboxErrors.toException(
            status = 409,
            body = """{"error_summary":"incorrect_offset/","error":{".tag":"incorrect_offset","correct_offset":4194304}}""",
        )
        assertEquals(CloudErrorKind.UPLOAD_SESSION_EXPIRED, error.kind)
    }

    @Test
    fun `a missing source object is not found`() {
        val error = DropboxErrors.toException(
            status = 409,
            body = """{"error_summary":"path/not_found/","error":{".tag":"path","path":{".tag":"not_found"}}}""",
        )
        assertEquals(CloudErrorKind.NOT_FOUND, error.kind)
    }

    @Test
    fun `an unrecognised failure is permanent rather than retried forever`() {
        val error = DropboxErrors.toException(status = 400, body = """{"error_summary":"something_new/"}""")
        assertEquals(CloudErrorKind.PERMANENT, error.kind)
        assertFalse(error.kind.isRetryable)
    }

    @Test
    fun `a body that is not json at all does not crash the mapping`() {
        val error = DropboxErrors.toException(status = 502, body = "<html>Bad Gateway</html>")
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, error.kind)
    }
}
