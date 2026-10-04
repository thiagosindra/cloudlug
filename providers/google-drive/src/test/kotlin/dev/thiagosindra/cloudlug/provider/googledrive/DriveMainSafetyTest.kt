package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.provider.Pkce
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Every blocking call in the Drive adapter leaves the caller's thread.
 *
 * The same property `:providers:dropbox`'s `MainSafetyTest` holds that adapter
 * to, written here because status.md's known gap 8 predicted the Drive adapter
 * would have the same shape and nothing would catch a repeat. The accounts
 * screen calls the connector, and through it the token client and
 * `authenticate()`, from `Dispatchers.Main`; Android answers a blocking call
 * there with `NetworkOnMainThreadException`, after the user has consented.
 *
 * Threads are compared by name without kotlinx's ` @coroutine#N` suffix, for
 * the reason Dropbox's test records: comparing decorated names passed whether
 * or not the call blocked.
 */
class DriveMainSafetyTest {

    private val server = MockWebServer()

    @Volatile
    private var requestThread: String? = null

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun recordingClient() = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                requestThread = Thread.currentThread().plainName()
                val local = chain.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            },
        )
        .build()

    private fun Thread.plainName(): String = name.substringBefore(" @")

    private fun pretendMain(): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, PRETEND_MAIN) }.asCoroutineDispatcher()

    /** Runs [block] on the pretend main thread, having checked it really is there. */
    private fun onMain(block: suspend () -> Unit) = runBlocking {
        pretendMain().use { main ->
            withContext(main) {
                assertEquals(PRETEND_MAIN, Thread.currentThread().plainName(), "not on the pretend main thread")
                block()
            }
        }
    }

    private fun assertLeftTheCallersThread() {
        assertNotNull(requestThread, "no HTTP call was made, so this proves nothing")
        assertNotEquals(PRETEND_MAIN, requestThread, "the request blocked the caller's thread")
    }

    @Test
    fun `the code exchange the connector makes leaves the caller's thread`() {
        server.enqueue(DriveFixtures.response("token_refresh_invalid_grant_400"))
        val tokens = DriveTokenClient(recordingClient(), tokenEndpoint = server.url("/token").toString())

        onMain { runCatching { tokens.exchangeCode("code", Pkce.newChallenge(), GoogleOAuth.ANDROID_REDIRECT_URI) } }

        assertLeftTheCallersThread()
    }

    @Test
    fun `authenticate's about_get leaves the caller's thread`() {
        server.enqueue(DriveFixtures.response("about_get_200"))

        onMain { GoogleDriveCloudProvider(StaticTokens(), recordingClient()).authenticate() }

        assertLeftTheCallersThread()
    }

    @Test
    fun `the picker's listing leaves the caller's thread`() {
        server.enqueue(DriveFixtures.response("files_list_nested_200"))

        onMain { GoogleDriveCloudProvider(StaticTokens(), recordingClient()).listChildren(ACCOUNT, DriveObjects.idOf("root")).toList() }

        assertLeftTheCallersThread()
    }

    @Test
    fun `preparing a destination leaves the caller's thread`() {
        server.enqueue(DriveFixtures.response("files_list_by_name_none_200"))
        server.enqueue(DriveFixtures.response("files_create_folder_200"))

        onMain {
            GoogleDriveCloudProvider(StaticTokens(), recordingClient())
                .prepareDestination(ACCOUNT, DriveObjects.idOf("root"), CloudPath.of("x"))
        }

        assertLeftTheCallersThread()
    }

    private companion object {
        const val PRETEND_MAIN = "pretend-main"
    }
}
