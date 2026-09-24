package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudSelection
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What [DropboxCloudProvider.enumerate] hands to the manifest, checked offline.
 *
 * ### Why this exists as a separate test
 *
 * The adapter's own contract suite is live-only: it needs a real account, so CI
 * without a token runs none of it. `ManifestBuilder`'s tests, meanwhile, run
 * against the fake provider. Both passed continuously while the one pairing
 * that ships — the Dropbox adapter feeding the real manifest builder — could
 * not complete a single transfer, because nothing anywhere ran the two
 * together and the contract suite's parent check was vacuous for an adapter
 * that set no parents at all.
 *
 * So these assert, against recorded Dropbox JSON and no network, the three
 * things `ManifestBuilder` will do to whatever `enumerate` emits: look up each
 * object's parent, expect the selection roots themselves, and expect no
 * request at all for a root that is not a folder.
 */
class DropboxEnumerationTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `the selected folder is emitted, followed by its descendants`() = runTest {
        respondWith(WORKSPACE to "list_folder_200", NESTED to "list_folder_nested_200")

        val emitted = provider().enumerate(ACCOUNT, selectionOf(folderObject(WORKSPACE, WORKSPACE_NAME))).toList()

        assertEquals(
            listOf(WORKSPACE_NAME, NESTED_NAME, LEAF_NAME),
            emitted.map { it.name },
            "§10 reproduces the source's own ancestors, so the selected folder is an item of the " +
                "transfer and not merely a cursor into one",
        )
    }

    @Test
    fun `every object below a root names the parent it was found under`() = runTest {
        respondWith(WORKSPACE to "list_folder_200", NESTED to "list_folder_nested_200")

        val emitted = provider().enumerate(ACCOUNT, selectionOf(folderObject(WORKSPACE, WORKSPACE_NAME))).toList()
        val byName = emitted.associateBy { it.name }

        // This is the property the crash came from. `ManifestBuilder` builds an
        // object's relative path by looking up `parentId` among the objects it
        // has already seen, and answers a null one with "emitted before its
        // parent". v0.3 asked Dropbox for the whole subtree in one recursive
        // call, which returns entries with no containment between them, so
        // every object arrived parentless and the first child aborted the walk.
        //
        // The two ids come out of the fixtures rather than being written here,
        // so a capture run that assigns different pseudonyms still exercises
        // the same containment.
        assertEquals(
            DropboxObjects.idOf(WORKSPACE),
            assertNotNull(byName.getValue(NESTED_NAME).parentId, "a folder below the root must name its parent"),
        )
        assertEquals(
            DropboxObjects.idOf(NESTED),
            assertNotNull(byName.getValue(LEAF_NAME).parentId, "a file must name the folder it was listed under"),
        )
    }

    @Test
    fun `a file chosen as the selection root is emitted without being listed`() = runTest {
        // §9 puts a checkbox on every row. Listing a file earns Dropbox's
        // `path/not_folder`, which is a 409 — and a 409 was all the user saw.
        alwaysRespond(409, Fixtures.raw("list_folder_not_folder_409"))

        val alone = CloudObject(
            id = DropboxObjects.idOf("id:alone"),
            name = "alone.txt",
            type = CloudObjectType.FILE,
            parentId = null,
            size = 4,
            modifiedAt = null,
            providerHash = null,
            revision = null,
            mimeType = null,
        )

        val emitted = provider().enumerate(ACCOUNT, selectionOf(alone)).toList()

        assertEquals(listOf("alone.txt"), emitted.map { it.name })
        assertEquals(0, server.requestCount, "a file root has nothing to list")
    }

    @Test
    fun `an unmapped refusal says which Dropbox error it was`() = runTest {
        alwaysRespond(409, """{"error_summary":"path/some_future_tag/","error":{".tag":"path"}}""")

        val failure = runCatching {
            provider().enumerate(ACCOUNT, selectionOf(folderObject("id:photos", "photos"))).toList()
        }.exceptionOrNull()

        val cloud = assertNotNull(failure as? CloudException)
        // "Dropbox returned 409" identifies nothing: 409 is the status Dropbox
        // uses for its whole route-specific error vocabulary, so the tag is the
        // only part that names the call. It is a tag path, so §26 is satisfied.
        assertTrue(
            "path/some_future_tag/" in cloud.message.orEmpty(),
            "an unmapped failure must name its error_summary, but said '${cloud.message}'",
        )
    }

    @Test
    fun `a file chosen as a root is not confused for a folder by the error mapping`() {
        // Straight from the fixture, so §23's mapping is checked against the
        // shape the adapter will actually be handed rather than against a
        // second guess at it.
        val mapped = DropboxErrors.toException(409, Fixtures.raw("list_folder_not_folder_409"))

        assertEquals("that object is a file, not a folder", mapped.message)
        // From the fixture, not written here. The hand-written version of this
        // body carried "path/not_folder/..." — the elided form — and Dropbox
        // sends "path/not_folder/". That is the second time a guess has
        // invented an ellipsis in an error_summary; the first is recorded in
        // ../errors/README.md.
        assertEquals(Fixtures.field("list_folder_not_folder_409", "error_summary"), mapped.code)
    }

    // ------------------------------------------------------------------ helpers

    private fun provider() = DropboxCloudProvider(StaticToken, localClient())

    /** [DropboxApi] builds its URLs from constants; this points them at the server. */
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

    /**
     * Answers `list_folder` per requested path, so the test does not depend on
     * the order the adapter happens to walk in.
     */
    private fun respondWith(vararg folders: Pair<String, String>) {
        val byPath = folders.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = PATH.find(request.body.readUtf8())?.groupValues?.get(1)
                val fixture = byPath[path] ?: return MockResponse().setResponseCode(409)
                    .setBody(Fixtures.raw("get_metadata_not_found_409"))
                return MockResponse().setResponseCode(200).setBody(Fixtures.raw(fixture))
            }
        }
    }

    private fun alwaysRespond(status: Int, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(status).setBody(body)
        }
    }

    private fun folderObject(id: String, name: String) = CloudObject(
        id = DropboxObjects.idOf(id),
        name = name,
        type = CloudObjectType.FOLDER,
        parentId = null,
        size = null,
        modifiedAt = null,
        providerHash = null,
        revision = null,
        mimeType = null,
    )

    private fun selectionOf(vararg roots: CloudObject) = CloudSelection.of(ACCOUNT, roots.toList())

    private object StaticToken : DropboxTokenSource {
        override suspend fun accessToken(account: AccountId) = "an-access-token"
        override suspend fun grantedScopes(account: AccountId) = DropboxOAuth.SCOPES.toSet()
        override suspend fun accountJustConnected() = ACCOUNT
    }

    private companion object {
        val ACCOUNT = AccountId("dbid:AAA")
        val PATH = """"path"\s*:\s*"([^"]*)"""".toRegex()

        // Read from the fixtures, never written here: a capture run assigns
        // its own pseudonyms, and a test that hardcoded them would start
        // failing for a reason that has nothing to do with the adapter.
        val WORKSPACE: String = Fixtures.metadata("create_folder_v2_200", "id")
        val WORKSPACE_NAME: String = Fixtures.metadata("create_folder_v2_200", "name")
        val NESTED: String = Fixtures.entryId("list_folder_200")
        val NESTED_NAME: String = Fixtures.entryName("list_folder_200")
        val LEAF_NAME: String = Fixtures.entryName("list_folder_nested_200")
    }
}
