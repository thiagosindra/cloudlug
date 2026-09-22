package dev.thiagosindra.cloudlug.provider.dropbox

import dev.thiagosindra.cloudlug.model.AccountId
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Every blocking call in this module must leave the caller's thread.
 *
 * `suspend` is not that promise and never was: it runs on whatever dispatcher
 * the caller is already on. OkHttp's `execute()` blocks, so a `suspend`
 * function that calls it without switching is main-unsafe while being
 * indistinguishable, at the call site, from one that is safe.
 *
 * Nothing caught that for three milestones. Every JVM test ran under
 * `runTest`, which has no opinion about blocking, and the only production
 * caller was the transfer engine, whose scope is `Dispatchers.IO` — so the
 * adapter was accidentally correct for the one caller it had. The accounts
 * screen called the same code from `viewModelScope`, and Android killed the
 * process with `NetworkOnMainThreadException` after the user had already
 * granted consent.
 *
 * So these tests assert the property rather than the path: run on a thread
 * this test owns, and check the HTTP call did not happen on it. A future
 * method that blocks without switching fails here rather than on somebody's
 * phone.
 */
class MainSafetyTest {

    private val server = MockWebServer()

    /** The thread OkHttp actually made the call on. */
    @Volatile
    private var requestThread: String? = null

    @AfterEach
    fun tearDown() = server.shutdown()

    /**
     * Records the calling thread, and points whatever host the code under test
     * chose at the local server — [DropboxApi] builds its URLs from constants,
     * and that is not worth loosening for a test.
     */
    private fun recordingClient() = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                requestThread = Thread.currentThread().plainName()
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
     * The thread's own name, without the ` @coroutine#N` that kotlinx.coroutines
     * appends while its debug probes are on.
     *
     * Not cosmetic. Comparing the decorated name against the bare one made the
     * assertion below pass whether or not the call had blocked — the first
     * version of this test was vacuous and only said so because of the sanity
     * check on the line it guards.
     */
    private fun Thread.plainName(): String = name.substringBefore(" @")

    /** A single named thread, standing in for Android's main looper. */
    private fun pretendMain(): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, PRETEND_MAIN) }
            .asCoroutineDispatcher()

    private fun assertLeftTheCallersThread() {
        assertNotNull(requestThread, "no HTTP call was made, so this proves nothing")
        assertNotEquals(
            PRETEND_MAIN,
            requestThread,
            "the request blocked the caller's thread. On Android that thread is the main " +
                "looper and this is NetworkOnMainThreadException — wrap the blocking call in " +
                "withContext(io) rather than relying on callers to pick a dispatcher.",
        )
    }

    @Test
    fun `a token exchange leaves the caller's thread`() = runBlocking {
        // The exact call that crashed: DropboxTokenClient.exchangeCode, reached
        // from the accounts screen's viewModelScope.
        server.enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":14400}"""))
        val tokens = DropboxTokenClient(recordingClient(), server.url("/oauth2/token").toString())

        pretendMain().use { main ->
            withContext(main) {
                assertEquals(
                    PRETEND_MAIN,
                    Thread.currentThread().plainName(),
                    "the test is not running where it thinks it is, so it proves nothing",
                )
                tokens.exchangeCode("a-code", PkceChallenge("v", "c", "s"))
            }
        }

        assertLeftTheCallersThread()
    }

    @Test
    fun `a refresh leaves the caller's thread`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh","expires_in":14400}"""))
        val tokens = DropboxTokenClient(recordingClient(), server.url("/oauth2/token").toString())

        pretendMain().use { main -> withContext(main) { tokens.refresh("stored") } }

        assertLeftTheCallersThread()
    }

    @Test
    fun `an rpc call leaves the caller's thread`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"account_id":"dbid:AAA"}"""))
        val api = DropboxApi(StaticToken, recordingClient())

        pretendMain().use { main -> withContext(main) { api.rpc(ACCOUNT, "/2/files/get_metadata") } }

        assertLeftTheCallersThread()
    }

    @Test
    fun `an argument-less rpc call leaves the caller's thread`() = runBlocking {
        // The one authenticate() makes, so this is the whole connect path
        // covered between this test and the exchange above.
        server.enqueue(MockResponse().setBody("""{"account_id":"dbid:AAA"}"""))
        val api = DropboxApi(StaticToken, recordingClient())

        pretendMain().use { main -> withContext(main) { api.rpcWithoutArgument(ACCOUNT, "/2/users/get_current_account") } }

        assertLeftTheCallersThread()
    }

    @Test
    fun `a content call leaves the caller's thread`() = runBlocking {
        server.enqueue(MockResponse().setBody("bytes").setHeader("Dropbox-API-Result", "{}"))
        val api = DropboxApi(StaticToken, recordingClient())

        pretendMain().use { main ->
            withContext(main) { api.content(ACCOUNT, "/2/files/download", kotlinx.serialization.json.buildJsonObject { }).close() }
        }

        assertLeftTheCallersThread()
    }

    @Test
    fun `an offset probe leaves the caller's thread`() = runBlocking {
        // §22.5's query, the one content path that reads its own failure body.
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error_summary":"incorrect_offset/"}"""))
        val api = DropboxApi(StaticToken, recordingClient())

        pretendMain().use { main ->
            withContext(main) {
                api.contentAllowingFailure(ACCOUNT, "/2/files/upload_session/append_v2", kotlinx.serialization.json.buildJsonObject { })
            }
        }

        assertLeftTheCallersThread()
    }

    private object StaticToken : DropboxTokenSource {
        override suspend fun accessToken(account: AccountId) = "an-access-token"
        override suspend fun grantedScopes(account: AccountId) = DropboxOAuth.SCOPES.toSet()
        override suspend fun accountJustConnected() = ACCOUNT
    }

    private companion object {
        const val PRETEND_MAIN = "pretend-main-looper"
        val ACCOUNT = AccountId("dbid:AAA")
    }
}
