package dev.thiagosindra.cloudlug.provider.googledrive

import dev.thiagosindra.cloudlug.model.AccountId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The recorded Drive responses the offline tests replay (`docs/testing.md`
 * rule 1), as `:tools:drive-capture` wrote them: a body per exchange, and
 * `manifest.json` with each one's status and the headers the adapter reads.
 *
 * Tests read ids and checksums *out of* the fixtures, so a capture run that
 * assigns different pseudonyms does not break them.
 */
internal object DriveFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    data class Entry(val name: String, val status: Int, val body: String, val headers: Map<String, String>)

    val manifest: Map<String, Entry> by lazy {
        json.parseToJsonElement(resource("manifest.json")).jsonArray.associate { element ->
            val o = element.jsonObject
            val name = o.text("name")
            name to Entry(
                name = name,
                status = o.text("status").toInt(),
                body = resource(o.text("body")),
                headers = (o["headers"] as JsonObject).mapValues { (it.value as JsonPrimitive).content },
            )
        }
    }

    fun entry(name: String): Entry = manifest[name]
        ?: error("no fixture '$name'. Capture it with :tools:drive-capture:captureDriveFixtures")

    fun obj(name: String): JsonObject = json.parseToJsonElement(entry(name).body).jsonObject

    fun field(name: String, key: String): String = obj(name).text(key)

    /** The recorded response, status, headers and body exactly as captured. */
    fun response(name: String): MockResponse = entry(name).let { e ->
        MockResponse().setResponseCode(e.status).setBody(e.body).apply {
            e.headers.forEach { (key, value) -> setHeader(key, value) }
        }
    }

    private fun resource(file: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$file")) {
            "no fixture file '$file'. Capture it with :tools:drive-capture:captureDriveFixtures"
        }.bufferedReader().readText()

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("fixture has no '$key'")
}

/** Every request, whatever host it names — including a captured session URI — goes to [server]. */
internal fun localClient(server: MockWebServer): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(
        Interceptor { chain ->
            val local = chain.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(chain.request().newBuilder().url(local).build())
        },
    )
    .build()

/** A token source for replay: one fixed token, and the scopes a test says were granted. */
internal class StaticTokens(
    private val scopes: Set<String> = setOf(GoogleOAuth.SCOPE_FILE),
) : DriveTokenSource {
    var bound: AccountId? = null
    override suspend fun accessToken(account: AccountId) = "replay-token"
    override suspend fun grantedScopes(account: AccountId) = scopes
    override suspend fun pendingAccessToken() = "replay-token"
    override suspend fun bindPending(account: AccountId) {
        bound = account
    }
    override suspend fun revoke(account: AccountId) = Unit
}

internal val ACCOUNT = AccountId("replay-account")
