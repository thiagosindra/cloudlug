package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.UploadRequest
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
import kotlin.test.assertNotNull

/**
 * What `finishUpload` makes of Dropbox's answer, checked offline.
 *
 * ### The bug this exists for
 *
 * `.tag` is present on a Dropbox value only where that value is a **union
 * member**. `list_folder` and `get_metadata` answer with `Metadata`, a union,
 * so every entry says `".tag": "file"`. `upload_session/finish` answers with a
 * `FileMetadata` **struct** — one possible type, so no member to name and no
 * tag in the body.
 *
 * The adapter read the tag as though it were always there, so it turned a
 * perfectly good upload response into null and threw. Dropbox had already
 * committed the file. Every transferred file therefore failed as
 * `error permanent` at the instant its bytes safely arrived, and a retry found
 * them all sitting at the destination as "duplicate verified by hash".
 *
 * ### Why it is tested here and not only live
 *
 * §31.2's `finishUpload reports the provider hash when the provider has one`
 * would have caught this on the first run. It is live-only for Dropbox, so CI
 * has never run it. That is exactly the gap `docs/testing.md` rule 1 names:
 * live tests prove the wire, recorded tests prove the handoff, and only one of
 * them runs on every PR.
 */
class DropboxUploadTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `a finished upload is the object the engine then verifies`() = runTest {
        serveUploadSession()
        val provider = DropboxCloudProvider(StaticToken, localClient())
        val parent = DropboxObjects.idOf("id:destinationfolder")

        val session = provider.beginUpload(
            ACCOUNT,
            UploadRequest(
                account = ACCOUNT,
                parent = parent,
                name = "quarterly.doc",
                size = SIZE,
                mimeType = null,
            ),
        )
        provider.uploadChunk(session, Chunk(0, ByteArray(8), 8, isFinal = true))
        val uploaded = provider.finishUpload(session)

        // §21 verifies with exactly these three, and an adapter that returns
        // none of them cannot complete an item however well the bytes landed.
        assertEquals(CloudObjectType.FILE, uploaded.type)
        assertEquals(SIZE, uploaded.size, "§21 compares the destination's size against what was sent")
        val hash = assertNotNull(
            uploaded.providerHash,
            "Dropbox declares supportsServerHash, so §21 has nothing to verify with if finishUpload drops it",
        )
        assertEquals(HashAlgorithm.DROPBOX_CONTENT_HASH, hash.algorithm)
        assertEquals(CONTENT_HASH, hash.value)
        assertEquals("a1c10ce0dd78", uploaded.revision, "§20.6 detects a source that changed mid-transfer")
    }

    @Test
    fun `a tagged union member is still read from its tag`() {
        // The struct fallback must not become a way to mislabel a union member.
        // `list_folder` says what each entry is, and that answer wins.
        val folder = assertNotNull(
            DropboxObjects.toCloudObject(
                json("""{".tag":"folder","id":"id:f","name":"2026"}"""),
                parent = null,
                assume = CloudObjectType.FILE,
            ),
        )
        assertEquals(CloudObjectType.FOLDER, folder.type)
    }

    @Test
    fun `a deleted entry is still nothing to transfer`() {
        assertEquals(
            null,
            DropboxObjects.toCloudObject(
                json("""{".tag":"deleted","name":"gone.txt","path_lower":"/gone.txt"}"""),
                parent = null,
                assume = CloudObjectType.FILE,
            ),
            "a tombstone names its tag, so the fallback must never see it",
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The three routes an upload takes, answering as Dropbox really does. */
    private fun serveUploadSession() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/upload_session/start") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"session_id":"ABCDEF"}""")

                request.path?.endsWith("/upload_session/append_v2") == true ->
                    MockResponse().setResponseCode(200).setBody("")

                // A FileMetadata struct: no `.tag`, because the route returns
                // only this one type. Recorded from a real finish response.
                request.path?.endsWith("/upload_session/finish") == true ->
                    MockResponse().setResponseCode(200).setBody(
                        """{"name":"quarterly.doc","id":"id:uploadedfile",""" +
                            """"client_modified":"2026-09-22T15:18:00Z",""" +
                            """"server_modified":"2026-09-22T15:18:01Z",""" +
                            """"rev":"a1c10ce0dd78","size":$SIZE,""" +
                            """"path_lower":"/destination/cloudlug - 2026-09-22 15-18/quarterly.doc",""" +
                            """"content_hash":"$CONTENT_HASH","is_downloadable":true}""",
                    )

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun json(raw: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(raw).let { it as kotlinx.serialization.json.JsonObject }

    private fun localClient() = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val local = chain.request().url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
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
        const val SIZE = 42_496L
        const val CONTENT_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
