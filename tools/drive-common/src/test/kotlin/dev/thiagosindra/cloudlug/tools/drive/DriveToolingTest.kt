package dev.thiagosindra.cloudlug.tools.drive

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The guards that stand between a tool that creates and deletes and the rest
 * of someone's Drive. Every body here is invented.
 */
class DriveToolingTest {

    private val server = MockWebServer()
    private val drive = DriveHttp(OkHttpClient(), "token", server.url("/"))

    @AfterTest
    fun stop() = server.shutdown()

    private fun respond(status: Int, body: String = "") =
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    @Test
    fun `the whole of My Drive is refused before any request is made`() {
        assertFailsWith<ToolFailure> { requireTestRoot(drive, "root") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a folder with another name is refused`() {
        respond(200, """{"id":"f1","name":"Photos","mimeType":"${DriveTooling.FOLDER_MIME}","trashed":false}""")
        assertFailsWith<ToolFailure> { requireTestRoot(drive, "f1") }
    }

    @Test
    fun `a file with the right name is refused`() {
        respond(200, """{"id":"f1","name":"${DriveTooling.TEST_ROOT_NAME}","mimeType":"text/plain","trashed":false}""")
        assertFailsWith<ToolFailure> { requireTestRoot(drive, "f1") }
    }

    @Test
    fun `the test root itself is accepted`() {
        respond(200, """{"id":"f1","name":"${DriveTooling.TEST_ROOT_NAME}","mimeType":"${DriveTooling.FOLDER_MIME}","trashed":false}""")
        assertEquals("f1", requireTestRoot(drive, "f1"))
    }

    @Test
    fun `no test root yet means one is created at the top of My Drive`() {
        respond(200, """{"files":[]}""")
        respond(200, """{"id":"new"}""")

        assertEquals(TestRoot.Created("new"), findOrCreateTestRoot(drive))

        val lookup = server.takeRequest()
        val query = lookup.requestUrl!!.queryParameter("q")!!
        assertTrue("'root' in parents" in query && DriveTooling.TEST_ROOT_NAME in query, query)
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertTrue("\"parents\":[\"root\"]" in create.body.readUtf8())
    }

    @Test
    fun `two folders with the name are reported rather than one picked`() {
        respond(200, """{"files":[{"id":"a"},{"id":"b"}]}""")

        val root = findOrCreateTestRoot(drive)

        assertIs<TestRoot.Ambiguous>(root)
        assertEquals(listOf("a", "b"), root.ids)
        assertEquals(1, server.requestCount, "nothing may be created when the answer is ambiguous")
    }

    @Test
    fun `the offset query is an empty PUT naming only the total`() {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))

        val answer = drive.queryUpload(server.url("/session?upload_id=x").toString(), 1_000_000)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("bytes */1000000", request.getHeader("Content-Range"))
        assertEquals(0, request.bodySize)
        assertEquals(308, answer.status)
        assertEquals("bytes=0-262143", answer.header("range"))
    }

    @Test
    fun `a chunk names its own byte range`() {
        server.enqueue(MockResponse().setResponseCode(308))
        val buffer = ByteArray(1024)

        drive.putChunk(server.url("/session").toString(), buffer, length = 512, offset = 262_144, total = 1_000_000)

        val request = server.takeRequest()
        assertEquals("bytes 262144-262655/1000000", request.getHeader("Content-Range"))
        assertEquals(512, request.bodySize)
    }

    @Test
    fun `query literals escape the quote Drive uses`() {
        assertEquals("""'it\'s'""", DriveHttp.queryLiteral("it's"))
    }
}
