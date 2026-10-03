package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * §23 for the failures that cannot be captured on demand.
 *
 * Throttling and a full account cannot be provoked without harming the
 * account (the fixtures README says so), so these bodies are **the captured
 * 404 envelope with only the status and the reason changed** — the one part
 * of the shape the capture could not supply. They are not a guess at the
 * envelope, which is exactly the thing that was guessed wrong for Dropbox.
 */
class DriveErrorMappingTest {

    private fun shaped(status: Int, reason: String): String =
        DriveFixtures.entry("files_get_not_found_404").body
            .replace("\"code\": 404", "\"code\": $status")
            .replace("\"reason\": \"notFound\"", "\"reason\": \"$reason\"")
            .also { check("\"$reason\"" in it) { "the captured envelope no longer has a reason to replace" } }

    @Test
    fun `each of the three 403 rate-limit reasons is throttling, not a permanent failure`() {
        listOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded").forEach { reason ->
            val mapped = DriveErrors.toException(403, shaped(403, reason))
            assertEquals(CloudErrorKind.THROTTLED, mapped.kind, reason)
            assertEquals(reason, mapped.code)
            assertTrue(mapped.kind.isRetryable)
        }
    }

    @Test
    fun `throttling honours Retry-After when Google sends it`() {
        val mapped = DriveErrors.toException(403, shaped(403, "userRateLimitExceeded"), retryAfter = 7.seconds)
        assertEquals(7.seconds, mapped.retryAfter)
    }

    @Test
    fun `a full account is a storage hold, not retried automatically`() {
        val mapped = DriveErrors.toException(403, shaped(403, "storageQuotaExceeded"))
        assertEquals(CloudErrorKind.DESTINATION_STORAGE_FULL, mapped.kind)
        assertEquals(false, mapped.kind.isRetryable)
    }

    @Test
    fun `any other 403 is permanent and names its reason`() {
        val mapped = DriveErrors.toException(403, shaped(403, "appNotAuthorizedToFile"))
        assertEquals(CloudErrorKind.PERMANENT, mapped.kind)
        assertTrue("appNotAuthorizedToFile" in mapped.message.orEmpty())
    }

    @Test
    fun `a 404 on a session URI is an expired session, and on a file is not found`() {
        val body = DriveFixtures.entry("files_get_not_found_404").body
        assertEquals(CloudErrorKind.UPLOAD_SESSION_EXPIRED, DriveErrors.toException(404, body, DriveErrors.Surface.UPLOAD_SESSION).kind)
        assertEquals(CloudErrorKind.NOT_FOUND, DriveErrors.toException(404, body).kind)
    }

    @Test
    fun `429 and 5xx retry, and a body that is not JSON is named as such`() {
        assertEquals(CloudErrorKind.THROTTLED, DriveErrors.toException(429, "").kind)
        val outage = DriveErrors.toException(503, "<html>")
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, outage.kind)
        assertEquals("Google Drive returned 503 (the body was not JSON)", outage.message)
    }

    @Test
    fun `no message quotes Drive's own prose, which can carry ids`() {
        val captured = DriveFixtures.entry("files_get_not_found_404").body
        val mapped = DriveErrors.toException(404, captured)
        assertTrue("File not found" !in mapped.message.orEmpty(), mapped.message)
    }
}
