package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudSelection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §8.2 item 3: being a source follows the granted scopes, not the adapter. */
class DriveSourceScopeTest {

    private val server = MockWebServer()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `this build's capabilities are a destination and not a source`() {
        assertTrue(DriveCapabilities.Default.canBeDestination)
        assertFalse(DriveCapabilities.Default.canBeSource)
        assertTrue(DriveCapabilities.forScopes(listOf(GoogleOAuth.SCOPE_FILE, GoogleOAuth.SCOPE_READONLY)).canBeSource)
    }

    private val folder = CloudObject(
        id = DriveObjects.idOf("folder"),
        name = "folder",
        type = CloudObjectType.FOLDER,
        parentId = null,
        size = null,
        modifiedAt = null,
        providerHash = null,
        revision = null,
        mimeType = null,
    )

    @Test
    fun `without a read scope, source calls are refused by name before any request`() = runTest {
        val drive = GoogleDriveCloudProvider(StaticTokens(setOf(GoogleOAuth.SCOPE_FILE)), localClient(server))

        val download = assertFailsWith<CloudException> { drive.openDownload(ACCOUNT, DriveObjects.idOf("f")) }
        val walk = assertFailsWith<CloudException> { drive.enumerate(ACCOUNT, CloudSelection.of(ACCOUNT, listOf(folder))).toList() }

        assertEquals("source_scope_not_granted", download.code)
        assertEquals("source_scope_not_granted", walk.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with drive_readonly granted, the same adapter downloads`() = runTest {
        server.enqueue(MockResponse().setBody("bytes"))
        val drive = GoogleDriveCloudProvider(
            StaticTokens(setOf(GoogleOAuth.SCOPE_FILE, GoogleOAuth.SCOPE_READONLY)),
            localClient(server),
        )

        drive.openDownload(ACCOUNT, DriveObjects.idOf("f"), 0L..3L).close()

        val request = server.takeRequest()
        assertEquals("media", request.requestUrl!!.queryParameter("alt"))
        assertEquals("bytes=0-3", request.getHeader("Range"))
    }
}
