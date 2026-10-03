package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.UploadRequest
import dev.thiagosindra.cloudlug.provider.UploadSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every route the capture recorded, replayed through the real adapter
 * (`docs/testing.md` rule 1).
 *
 * The last test fails when a capture run records a route that no test here
 * replays, so a new fixture cannot sit in the directory unexercised.
 */
class DriveCapturedRoutesTest {

    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun provider(tokens: DriveTokenSource = StaticTokens()) =
        GoogleDriveCloudProvider(tokens, localClient(server), clock = { now })

    private fun replay(vararg names: String) = names.forEach { server.enqueue(DriveFixtures.response(it)) }

    private val root get() = DriveObjects.idOf(DriveObjects.ROOT)

    // ---------------------------------------------------------------- about.get

    @Test
    fun `about names the account by permissionId and files the pending grant under it`() = runTest {
        replay("about_get_200")
        val tokens = StaticTokens()

        val account = provider(tokens).authenticate()

        val user = DriveFixtures.obj("about_get_200")["user"] as kotlinx.serialization.json.JsonObject
        val permissionId = (user["permissionId"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(permissionId, account.id.value)
        assertEquals(account.id, tokens.bound, "the grant must be filed under the account about.get named")
        assertEquals("scratch@example.com", account.displayEmail)
        assertEquals(setOf(GoogleOAuth.SCOPE_FILE), account.grantedScopes)
    }

    @Test
    fun `the real storageQuota parses into a quota the engine can refuse on`() = runTest {
        replay("about_get_200")

        val quota = assertNotNull(provider().quota(ACCOUNT))

        val recorded = DriveFixtures.obj("about_get_200")["storageQuota"] as kotlinx.serialization.json.JsonObject
        val limit = (recorded["limit"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        val usage = (recorded["usage"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        assertEquals(limit, quota.totalBytes)
        assertEquals(usage, quota.usedBytes)
        assertEquals(limit - usage, quota.availableBytes)
    }

    // ------------------------------------------------------------ listing (§9)

    @Test
    fun `listChildren follows nextPageToken and names the parent on every child`() = runTest {
        replay("files_list_by_parent_page1_200", "files_list_by_parent_page2_200")
        val workspace = DriveObjects.idOf("workspace")

        val children = provider().listChildren(ACCOUNT, workspace).toList()

        // Anchored on the positive case: three children over two real pages.
        assertEquals(3, children.size, "expected both duplicates and the nested folder, got ${children.map { it.name }}")
        assertTrue(children.all { it.parentId == workspace })
        assertEquals(setOf("duplicate.bin", "nested"), children.map { it.name }.toSet())

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals(DriveFixtures.field("files_list_by_parent_page1_200", "nextPageToken"), second.requestUrl!!.queryParameter("pageToken"))
        assertEquals("drive", second.requestUrl!!.queryParameter("spaces"), "§20.8: My Drive only")
    }

    @Test
    fun `a nested folder lists its file, with a size and both checksums`() = runTest {
        replay("files_list_nested_200")

        val file = provider().listChildren(ACCOUNT, DriveObjects.idOf("nested")).toList().single()

        assertEquals(CloudObjectType.FILE, file.type)
        assertNotNull(file.size)
        assertEquals(HashAlgorithm.MD5, file.providerHash?.algorithm)
        assertEquals(HashAlgorithm.SHA256, file.additionalHashes.single().algorithm)
    }

    // ------------------------------------------------------------- §19.3 lookup

    @Test
    fun `lookupDestination returns every same-name sibling, so the collision algorithm calls it a conflict`() = runTest {
        replay("files_list_by_name_duplicates_200")

        val matches = provider().lookupDestination(ACCOUNT, DriveObjects.idOf("workspace"), "duplicate.bin")

        assertEquals(2, matches.size)
        assertEquals(2, matches.map { it.id }.toSet().size, "two siblings, not one object twice")
        val query = server.takeRequest().requestUrl!!.queryParameter("q")!!
        assertTrue("name = 'duplicate.bin'" in query && "trashed = false" in query, query)
    }

    @Test
    fun `lookupDestination with no match is empty`() = runTest {
        replay("files_list_by_name_none_200")
        assertEquals(emptyList(), provider().lookupDestination(ACCOUNT, root, "absent.bin"))
        assertEquals(1, server.requestCount, "the empty answer came from a real query, not from skipping it")
    }

    // -------------------------------------------------------------- files.get

    @Test
    fun `resolveMetadata reads size, version and both checksums`() = runTest {
        replay("files_get_file_200")

        val file = provider().resolveMetadata(ACCOUNT, DriveObjects.idOf(DriveFixtures.field("files_get_file_200", "id")))

        assertEquals(DriveFixtures.field("files_get_file_200", "size").toLong(), file.size)
        assertEquals(DriveFixtures.field("files_get_file_200", "md5Checksum"), file.providerHash?.value)
        assertEquals(DriveFixtures.field("files_get_file_200", "sha256Checksum"), file.additionalHashes.single().value)
        assertEquals(DriveFixtures.field("files_get_file_200", "version"), file.revision)
    }

    @Test
    fun `a folder resolves as a folder with no size`() = runTest {
        replay("files_get_folder_200")
        val folder = provider().resolveMetadata(ACCOUNT, DriveObjects.idOf("nested"))
        assertEquals(CloudObjectType.FOLDER, folder.type)
        assertNull(folder.size)
    }

    // ------------------------------------------------------ §10 prepareDestination

    @Test
    fun `prepareDestination creates a folder that does not exist yet`() = runTest {
        replay("files_list_by_name_none_200", "files_create_folder_200")

        val prepared = provider().prepareDestination(ACCOUNT, root, CloudPath.of("capture"))

        assertTrue(prepared.created)
        assertEquals(DriveFixtures.field("files_create_folder_200", "id"), prepared.id.opaqueId)
        server.takeRequest()
        val create = server.takeRequest()
        assertTrue("\"mimeType\":\"${DriveObjects.FOLDER_MIME}\"" in create.body.readUtf8())
    }

    @Test
    fun `prepareDestination reuses the folder it finds, so a resume makes no second one`() = runTest {
        // A real listing whose only entry is the folder named `nested`.
        replay("files_list_by_parent_page2_200")

        val prepared = provider().prepareDestination(ACCOUNT, DriveObjects.idOf("workspace"), CloudPath.of("nested"))

        assertEquals(false, prepared.created)
        assertEquals(1, server.requestCount, "no create may follow a lookup that found the folder")
    }

    @Test
    fun `a folder chain creates each level under the one before it`() = runTest {
        replay("files_list_by_name_none_200", "files_create_folder_200", "files_list_by_name_none_200", "files_create_folder_nested_200")

        val prepared = provider().prepareDestination(ACCOUNT, root, CloudPath.of("capture/nested"))

        assertEquals(DriveFixtures.field("files_create_folder_nested_200", "id"), prepared.id.opaqueId)
        val requests = List(4) { server.takeRequest() }
        val outer = DriveFixtures.field("files_create_folder_200", "id")
        assertTrue("\"parents\":[\"$outer\"]" in requests[3].body.readUtf8(), "the inner folder must be created inside the outer one")
        assertTrue("'$outer' in parents" in requests[2].requestUrl!!.queryParameter("q")!!)
    }

    @Test
    fun `creating under a parent that is gone is not found`() = runTest {
        replay("files_list_by_name_none_200", "files_create_folder_parent_not_found_404")
        val failure = assertFailsWith<CloudException> {
            provider().prepareDestination(ACCOUNT, DriveObjects.idOf("gone"), CloudPath.of("x"))
        }
        assertEquals(CloudErrorKind.NOT_FOUND, failure.kind)
    }

    // ------------------------------------------------- resumable upload (§22.5)

    private fun request(size: Long) = UploadRequest(ACCOUNT, root, "captured.bin", size, "application/octet-stream")

    private val captured = ByteArray(256 * 1024 + 1000) { (it % 251).toByte() }

    @Test
    fun `beginUpload keeps the session URI and an expiry inside Google's week`() = runTest {
        replay("upload_initiate_200")

        val session = provider().beginUpload(ACCOUNT, request(captured.size.toLong()))

        val location = DriveFixtures.entry("upload_initiate_200").headers.getValue("location")
        assertEquals(location, session.providerMetadata)
        assertTrue(session.id.isNotBlank() && session.id in location, "the id is the session's upload_id")
        assertEquals(now.plus(GoogleDriveCloudProvider.SESSION_LIFETIME), session.expiresAt)
        val initiate = server.takeRequest()
        assertEquals(captured.size.toString(), initiate.getHeader("X-Upload-Content-Length"))
        assertEquals("resumable", initiate.requestUrl!!.queryParameter("uploadType"))
    }

    private fun session() = UploadSession(
        id = "session",
        request = request(captured.size.toLong()),
        providerMetadata = DriveFixtures.entry("upload_initiate_200").headers.getValue("location"),
    )

    @Test
    fun `a session that has received nothing reports zero, from the absence of Range`() = runTest {
        replay("upload_status_nothing_received_308")
        assertEquals(0L, provider().queryUpload(session()).acknowledgedBytes)
        assertEquals("bytes */${captured.size}", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun `an aligned chunk is acknowledged by the Range Drive returns`() = runTest {
        replay("upload_chunk_308")

        val progress = provider().uploadChunk(session(), Chunk(0, captured.copyOf(256 * 1024)))

        assertEquals(256L * 1024, progress.acknowledgedBytes)
        assertEquals(false, progress.complete)
        assertEquals("bytes 0-262143/${captured.size}", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun `the offset query reports what Drive holds`() = runTest {
        replay("upload_status_308")
        assertEquals(256L * 1024, provider().queryUpload(session()).acknowledgedBytes)
    }

    @Test
    fun `a chunk re-sent from zero is accepted, as the recovery pass needs`() = runTest {
        replay("upload_chunk_resent_from_zero")
        val progress = provider().uploadChunk(session(), Chunk(0, captured.copyOf(256 * 1024)))
        assertEquals(256L * 1024, progress.acknowledgedBytes)
    }

    @Test
    fun `the final chunk returns the file, and finishUpload hands back its checksums`() = runTest {
        replay("upload_finish_200")
        val provider = provider()
        val session = session()

        val progress = provider.uploadChunk(
            session,
            Chunk(256L * 1024, captured.copyOfRange(256 * 1024, captured.size), isFinal = true),
        )
        val file = provider.finishUpload(session)

        assertTrue(progress.complete)
        assertEquals(DriveFixtures.field("upload_finish_200", "md5Checksum"), file.providerHash?.value)
        assertEquals(DriveFixtures.field("upload_finish_200", "sha256Checksum"), file.additionalHashes.single().value)
        assertEquals(1, server.requestCount, "finishUpload commits nothing more; the final chunk did")
        assertEquals("bytes 262144-${captured.size - 1}/${captured.size}", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun `after process death, finishUpload recovers the file from the completed session`() = runTest {
        replay("upload_status_after_finish")

        // A fresh provider: nothing in memory knows the final chunk landed.
        val file = provider().finishUpload(session())

        assertEquals(DriveFixtures.field("upload_status_after_finish", "md5Checksum"), file.providerHash?.value)
    }

    @Test
    fun `a short acknowledgement is retried rather than trusted`() = runTest {
        // The real answer to a 300000-byte chunk: Drive kept the aligned
        // 262144 and dropped the rest.
        replay("upload_chunk_unaligned")

        val failure = assertFailsWith<CloudException> {
            provider().uploadChunk(session(), Chunk(0, ByteArray(512 * 1024)))
        }
        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, failure.kind)
    }

    @Test
    fun `an unaligned non-final chunk is refused before it is sent`() = runTest {
        assertFailsWith<IllegalArgumentException> { provider().uploadChunk(session(), Chunk(0, ByteArray(300_000))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `abortUpload accepts Drive's 499`() = runTest {
        replay("upload_cancel")
        provider().abortUpload(session())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `a cancelled session is expired, so the item restarts its upload`() = runTest {
        replay("upload_status_cancelled")
        val failure = assertFailsWith<CloudException> { provider().queryUpload(session()) }
        assertEquals(CloudErrorKind.UPLOAD_SESSION_EXPIRED, failure.kind)
    }

    // ---------------------------------------------------------------- §23 errors

    @Test
    fun `a deleted file is not found`() = runTest {
        replay("files_get_not_found_404")
        val failure = assertFailsWith<CloudException> { provider().resolveMetadata(ACCOUNT, DriveObjects.idOf("gone")) }
        assertEquals(CloudErrorKind.NOT_FOUND, failure.kind)
        assertEquals("notFound", failure.code)
    }

    @Test
    fun `a malformed query is permanent and names Drive's reason`() = runTest {
        replay("files_list_bad_query_400")
        val failure = assertFailsWith<CloudException> { provider().lookupDestination(ACCOUNT, root, "x") }
        assertEquals(CloudErrorKind.PERMANENT, failure.kind)
        assertEquals("invalid", failure.code)
    }

    @Test
    fun `an invalid token is AUTH_REQUIRED`() = runTest {
        replay("files_get_invalid_token_401")
        val failure = assertFailsWith<CloudException> { provider().resolveMetadata(ACCOUNT, DriveObjects.idOf("x")) }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
    }

    @Test
    fun `a refresh answered invalid_grant is AUTH_REQUIRED, the Testing-status seven-day expiry`() = runTest {
        replay("token_refresh_invalid_grant_400")
        val tokens = DriveTokenClient(localClient(server), tokenEndpoint = server.url("/token").toString())
        val failure = assertFailsWith<CloudException> { tokens.refresh("expired") }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, failure.kind)
    }

    @Test
    fun `files_delete answers 204 with no body, which the live suite's clean-up relies on`() = runTest {
        replay("files_delete_204")
        DriveApi(StaticTokens(), localClient(server)).delete(ACCOUNT, "anything")
        assertEquals("DELETE", server.takeRequest().method)
    }

    // ------------------------------------------------------------- coverage

    @Test
    fun `every captured route is replayed by a test in this file`() {
        val uncovered = DriveFixtures.manifest.keys - COVERED
        assertEquals(emptySet(), uncovered, "captured routes no test replays")
        // Sanity: the manifest is not empty, so the set difference had something to check.
        assertTrue(DriveFixtures.manifest.size >= COVERED.size)
    }

    private companion object {
        val COVERED = setOf(
            "about_get_200",
            "files_create_folder_200",
            "files_create_folder_nested_200",
            "files_list_by_parent_page1_200",
            "files_list_by_parent_page2_200",
            "files_list_nested_200",
            "files_list_by_name_duplicates_200",
            "files_list_by_name_none_200",
            "files_get_file_200",
            "files_get_folder_200",
            "files_delete_204",
            "files_get_not_found_404",
            "files_create_folder_parent_not_found_404",
            "files_list_bad_query_400",
            "files_get_invalid_token_401",
            "token_refresh_invalid_grant_400",
            "upload_initiate_200",
            "upload_status_nothing_received_308",
            "upload_chunk_308",
            "upload_status_308",
            "upload_chunk_resent_from_zero",
            "upload_finish_200",
            "upload_status_after_finish",
            "upload_chunk_unaligned",
            "upload_cancel",
            "upload_status_cancelled",
        )
    }
}
