package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.UploadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routes the first capture run added, checked against what the adapter
 * assumes about them.
 *
 * Until that run these seven had no offline coverage at all: the quota parse,
 * §22.5's offset recovery, an expired token, a folder that already exists, a
 * path Dropbox rejects before the route runs, an empty append acknowledgement,
 * and paging. Each is exercised here against the recorded body rather than
 * against a description of it — `docs/testing.md` rule 1, and the reason the
 * rule exists is that two of this adapter's three shipped bugs were a
 * plausible guess about a shape.
 */
class DropboxCapturedRoutesTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    // ------------------------------------------------------------- quota (§20.7)

    @Test
    fun `the real get_space_usage parses into a quota the engine can refuse on`() = runTest {
        respond(200, Fixtures.raw("get_space_usage_200"))

        val quota = assertNotNull(provider().quota(ACCOUNT), "an individual account reports an allocation")

        // §31.2's quota check was vacuous until PR #17 — it skipped a null
        // quota and then skipped a half-filled one — so no test has ever
        // asserted that this parse works. These are the account's real numbers.
        assertEquals(Fixtures.number("get_space_usage_200", "used"), quota.usedBytes)
        assertEquals(2_147_483_648L, quota.totalBytes)
        assertEquals(quota.totalBytes!! - quota.usedBytes!!, quota.availableBytes)
    }

    // ------------------------------------------------- §22.5 offset recovery

    @Test
    fun `an incorrect offset is restartable and carries where the session really is`() {
        val body = Fixtures.raw("upload_session_incorrect_offset_409")
        val mapped = DropboxErrors.toException(409, body)

        // This pair is the whole of §22.5: the kind says "restart the upload",
        // and the offset says where from. A transfer resumed after process
        // death has nothing else to go on.
        assertEquals(CloudErrorKind.UPLOAD_SESSION_EXPIRED, mapped.kind)
        assertEquals(1024L, DropboxErrors.correctOffsetOf(body))
    }

    @Test
    fun `the offset is read as a number, not swept up as a tag`() {
        // `tagsOf` walks the whole error node collecting strings. correct_offset
        // is a JSON number, so it must not be mistaken for a tag — if it were,
        // a future tag named after a digit string would be a real hazard.
        val mapped = DropboxErrors.toException(409, Fixtures.raw("upload_session_incorrect_offset_409"))
        assertFalse("1024" in mapped.code.orEmpty())
    }

    // ------------------------------------------------------------------ §23

    @Test
    fun `a real expired token is auth required`() {
        val mapped = DropboxErrors.toException(401, Fixtures.raw("get_metadata_invalid_token_401"))

        assertEquals(CloudErrorKind.AUTH_REQUIRED, mapped.kind)
        assertEquals("invalid_access_token/", mapped.code)
    }

    @Test
    fun `a folder that already exists is permanent, and says which conflict it was`() {
        val mapped = DropboxErrors.toException(409, Fixtures.raw("create_folder_v2_conflict_409"))

        // §10's prepareDestination resolves each segment by path before
        // creating it, so this only arrives when something else made the folder
        // between the two calls. PERMANENT is the safe answer for that race —
        // it stops rather than guessing — and the tag is what tells a reader it
        // was a conflict and not a refusal.
        assertEquals(CloudErrorKind.PERMANENT, mapped.kind)
        assertEquals("path/conflict/folder/", mapped.code)
    }

    @Test
    fun `a path Dropbox rejects before the route runs is permanent and is not echoed`() {
        val body = Fixtures.raw("get_metadata_malformed_path_400")
        val mapped = DropboxErrors.toException(400, body)

        // Argument validation answers in plain prose, not a tagged union: there
        // is no `malformed_path` tag here to map on, which is worth knowing
        // because §23's mapping has a branch for that tag and it does not fire
        // for this.
        assertEquals(CloudErrorKind.PERMANENT, mapped.kind)
        assertNull(mapped.code, "there is no error_summary in a non-JSON body")
        assertTrue("not JSON" in mapped.message.orEmpty(), "got: ${mapped.message}")

        // §26. This class of body quotes the argument it rejected, and the
        // argument is a path.
        assertFalse(
            "files/get_metadata" in mapped.message.orEmpty() || "regex" in mapped.message.orEmpty(),
            "the body reached the message: ${mapped.message}",
        )
    }

    // ------------------------------------------------------- upload and paging

    @Test
    fun `an append acknowledgement is the literal null, and the adapter does not read it`() = runTest {
        // Recorded: the body is `null`, four bytes — not empty, which is what
        // the hand-written fixture assumed. `uploadChunk` closes the response
        // without parsing, so this is fine; it is pinned because `rpc` and
        // `rpcWithoutArgument` *do* parse, and `parseToJsonElement("null")`
        // yields JsonNull, whose `.jsonObject` throws. A route that answers
        // `null` must never be read through those two.
        assertEquals("null", Fixtures.raw("upload_session_append_v2_200").trim())

        respondByPath(
            "/upload_session/start" to Fixtures.raw("upload_session_start_200"),
            "/upload_session/append_v2" to Fixtures.raw("upload_session_append_v2_200"),
        )
        val provider = provider()
        val session = provider.beginUpload(
            ACCOUNT,
            UploadRequest(ACCOUNT, DropboxObjects.idOf("id:parent"), "x.bin", 8, null),
        )

        // Final, because §22's chunk alignment refuses a short non-final one:
        // a chunk boundary has to be a 4 MiB hash-block boundary.
        val progress = provider.uploadChunk(session, Chunk(0, ByteArray(8), 8, isFinal = true))

        assertEquals(8L, progress.acknowledgedBytes)
    }

    @Test
    fun `a listing that says it has no more pages does not ask for another`() = runTest {
        respondByPath("/list_folder" to Fixtures.raw("list_folder_paged_200"))

        val children = provider().listChildren(ACCOUNT, DropboxObjects.idOf("id:parent")).toList()

        assertEquals(1, children.size)
        // `has_more` is false, so `list_folder/continue` must not be called.
        // Note what this does *not* cover: the capture asked for limit=1 but
        // the workspace held one entry, so Dropbox had no second page to give
        // and `list_folder_continue_200` came back empty. Paging across a real
        // `has_more: true` is still unexercised offline; capturing it needs a
        // workspace seeded with more entries than the limit.
        assertEquals(1, server.requestCount, "a single page must not trigger a continue call")
    }

    // ------------------------------------------------------------------ helpers

    private fun provider() = DropboxCloudProvider(StaticToken, localClient())

    private fun respond(status: Int, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(status).setBody(body)
        }
    }

    private fun respondByPath(vararg routes: Pair<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = routes.firstOrNull { request.path?.endsWith(it.first) == true }?.second
                    ?: return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
    }

    private fun localClient() = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val local = chain.request().url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            },
        )
        .build()

    private object StaticToken : DropboxTokenSource {
        override suspend fun accessToken(account: AccountId) = "an-access-token"
        override suspend fun grantedScopes(account: AccountId) = DropboxOAuth.SCOPES.toSet()
        override suspend fun accountJustConnected() = ACCOUNT
    }

    private companion object {
        val ACCOUNT = AccountId("dbid:AAA")
    }
}
