package dev.thiagosindra.cloudlug.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The interceptor against a real OkHttp call, because the unit tests above
 * check the redactor in isolation and the thing that matters is what actually
 * reaches the sink.
 */
class RedactingLogInterceptorTest {

    private val server = MockWebServer()
    private val lines = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .addInterceptor(RedactingLogInterceptor({ lines += it }))
        .build()

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `a request carrying a bearer token logs neither the token nor the body`() {
        val token = "sl.B7xKq-notarealtoken-9f2Za"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"$token","content_hash":"ff12"}"""),
        )

        val request = Request.Builder()
            .url(server.url("/2/files/upload?access_token=$token"))
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", """{"path":"/Taxes/2025 return.pdf"}""")
            .post("some file bytes".toRequestBody())
            .build()

        client.newCall(request).execute().use { it.body?.string() }

        val logged = lines.joinToString("\n")
        assertTrue(lines.isNotEmpty(), "nothing was logged")
        assertFalse(token in logged, "the token reached the log:\n$logged")
        assertFalse("2025 return.pdf" in logged, "a filename reached the log:\n$logged")
        assertFalse("some file bytes" in logged, "the request body reached the log:\n$logged")
        assertFalse("content_hash" in logged, "the response body reached the log:\n$logged")

        // Still useful: the route and the outcome.
        assertTrue("/2/files/upload" in logged, "the path is missing:\n$logged")
        assertTrue("200" in logged, "the status is missing:\n$logged")
    }

    @Test
    fun `a failed call logs the failure without the token`() {
        val token = "sl.another-notarealtoken"
        server.shutdown() // nothing is listening

        val request = Request.Builder()
            .url(server.url("/2/files/upload"))
            .header("Authorization", "Bearer $token")
            .build()

        runCatching { client.newCall(request).execute() }

        val logged = lines.joinToString("\n")
        assertFalse(token in logged, "the token reached the log on the failure path:\n$logged")
        assertTrue(logged.contains("failed"), "the failure was not logged:\n$logged")
    }
}
