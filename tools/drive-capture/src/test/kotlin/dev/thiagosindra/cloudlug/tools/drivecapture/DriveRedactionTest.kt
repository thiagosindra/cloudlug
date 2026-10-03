package dev.thiagosindra.cloudlug.tools.drivecapture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of the capture tool that can be checked without an account, and
 * the half where a mistake is unrecoverable because a fixture is committed.
 *
 * Every value here is invented, with the length and alphabet of the real
 * thing — never a real id pasted in to prove it gets removed.
 */
class DriveRedactionTest {

    private val folderId = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val fileId = "1ZyXwVuTsRqPoNmLkJiHgFeDcBa987654"

    private fun harvested(vararg bodies: String) = DriveRedaction().apply { bodies.forEach(::harvest) }

    @Test
    fun `an id keeps one pseudonym across files, including inside parents`() {
        val folder = """{"id": "$folderId", "name": "nested"}"""
        val child = """{"id": "$fileId", "parents": ["$folderId"]}"""
        val redaction = harvested(folder, child)

        val redactedFolder = redaction.redact(folder)
        val redactedChild = redaction.redact(child)
        val pseudonym = Regex("\"id\": \"([^\"]+)\"").find(redactedFolder)!!.groupValues[1]

        assertTrue("\"parents\": [\"$pseudonym\"]" in redactedChild, redactedChild)
        assertFalse(folderId in redactedFolder + redactedChild)
        assertFalse(fileId in redactedChild)
    }

    @Test
    fun `an id is replaced where no id field names it`() {
        val error = """{"error": {"message": "File not found: $fileId."}}"""
        val redaction = harvested("""{"id": "$fileId"}""", error)

        val redacted = redaction.redact(error)

        assertFalse(fileId in redacted, redacted)
        assertTrue(redacted.startsWith("""{"error": {"message": "File not found: """))
    }

    @Test
    fun `pseudonyms keep length and alphabet`() {
        val body = """{"id": "$fileId", "nextPageToken": "~!-page-token-with-an-unusual-shape-0123"}"""
        val redacted = harvested(body).redact(body)

        assertEquals(body.length, redacted.length)
        val id = Regex("\"id\": \"([^\"]+)\"").find(redacted)!!.groupValues[1]
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' }, id)
    }

    @Test
    fun `the upload id in a session URI is replaced, the rest of the URI kept`() {
        val uploadId = "AHVrFxNotARealUploadId_0123456789-abcdefghijklmnopqrstuvwxyz"
        val location = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=$uploadId"
        val redacted = harvested(location).redact(location)

        assertFalse(uploadId in redacted)
        assertEquals(location.length, redacted.length)
        assertTrue(redacted.startsWith("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id="))
    }

    @Test
    fun `the account's name and address become invented ones`() {
        val about = """{"user": {"displayName": "Someone Real", "emailAddress": "someone@example.net", "permissionId": "01234567890123456789"}}"""
        val redaction = harvested(about)

        val redacted = redaction.redact(about)

        assertFalse("Someone Real" in redacted || "someone@" in redacted || "01234567890123456789" in redacted, redacted)
        assertTrue("\"emailAddress\": \"scratch@example.com\"" in redacted, redacted)
        assertEquals(emptyList(), redaction.leaks(redacted))
    }

    @Test
    fun `a value that survives is reported, and so is a stray address`() {
        val body = """{"id": "$fileId"}"""
        val redaction = harvested(body)

        // Sanity: the check can fail. Unredacted text must leak.
        assertTrue(redaction.leaks(body).isNotEmpty())
        assertTrue(redaction.leaks("contact owner@example.org").isNotEmpty())
        assertTrue(redaction.leaks("ok: fixture@example.test").isEmpty())
    }

    @Test
    fun `anything shaped like a Google token is refused`() {
        val redaction = DriveRedaction()
        assertTrue(redaction.leaks("""{"access_token": "ya29.not-a-real-token-but-shaped"}""").isNotEmpty())
        assertTrue(redaction.leaks("""{"refresh_token": "1//not-a-real-refresh-token-shape"}""").isNotEmpty())
    }

    @Test
    fun `checksums, sizes and short values are left alone`() {
        val body = """{"id": "$fileId", "size": "262144", "md5Checksum": "0cc175b9c0f1b6a831c399e269772661", "version": "3"}"""
        val redacted = harvested(body).redact(body)

        assertTrue("\"md5Checksum\": \"0cc175b9c0f1b6a831c399e269772661\"" in redacted)
        assertTrue("\"size\": \"262144\"" in redacted && "\"version\": \"3\"" in redacted)
    }

    @Test
    fun `every parameter in a session URI is redacted unless CloudLug sent it`() {
        // The first real capture carried a session_crd nobody had named. The
        // values here are invented, in the shape of what came back.
        val location = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable" +
            "&fields=id%2Cname&upload_id=AHVrFxNotARealUploadId_0123456789-abcdefghij" +
            "&session_crd=AHSoBRNotARealSessionCredential_0123456789-abcdefghijklmnop&x=7"
        val redaction = harvested(location)

        val redacted = redaction.redact(location)

        assertFalse("NotAReal" in redacted, redacted)
        assertFalse("&x=7" in redacted, "a short unknown value is still state: $redacted")
        assertTrue("uploadType=resumable&fields=id%2Cname&upload_id=FIXTURE" in redacted, redacted)
        assertEquals(location.length, redacted.length)
        assertEquals(emptyList(), redaction.leaks(redacted))
    }

    @Test
    fun `an unknown parameter that was never harvested is refused, not published`() {
        // Sanity for the refusal itself: a redactor that misses a parameter
        // must still be stopped by leaks().
        val leaks = DriveRedaction().leaks("""{"location": "https://example.invalid/u?uploadType=resumable&novel_state=abc123"}""")
        assertEquals(listOf("an unredacted URL parameter 'novel_state'"), leaks)
    }

    @Test
    fun `a short query value is replaced only inside its URL`() {
        val text = """{"size": "7", "location": "https://example.invalid/u?x=7"}"""
        val redacted = harvested(text).redact(text)
        assertTrue("\"size\": \"7\"" in redacted, redacted)
        assertFalse("?x=7" in redacted, redacted)
    }
}
