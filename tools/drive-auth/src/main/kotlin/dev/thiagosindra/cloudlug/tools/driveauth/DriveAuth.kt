package dev.thiagosindra.cloudlug.tools.driveauth

import com.sun.net.httpserver.HttpServer
import dev.thiagosindra.cloudlug.provider.Pkce
import dev.thiagosindra.cloudlug.provider.PkceChallenge
import dev.thiagosindra.cloudlug.provider.googledrive.GoogleOAuth
import dev.thiagosindra.cloudlug.tools.drive.DriveHttp
import dev.thiagosindra.cloudlug.tools.drive.DriveTooling
import dev.thiagosindra.cloudlug.tools.drive.TestRoot
import dev.thiagosindra.cloudlug.tools.drive.findOrCreateTestRoot
import dev.thiagosindra.cloudlug.tools.drive.parseObject
import dev.thiagosindra.cloudlug.tools.drive.runTool
import dev.thiagosindra.cloudlug.tools.drive.string
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Mints a Google Drive refresh token for `DRIVE_REFRESH_TOKEN`, and finds or
 * creates the test root the live tests write beneath.
 *
 * Same shape as `dropbox-auth`, one difference forced by Google: there is no
 * "show the code on screen" mode any more (the out-of-band flow was retired in
 * 2022), so this listens on a loopback port for the redirect. That needs a
 * **Desktop** OAuth client — Google disallows loopback for Android clients —
 * and a Desktop client's token exchange needs its `client_secret` even under
 * PKCE. The secret comes from `DRIVE_TOOL_CLIENT_SECRET` and goes nowhere but
 * the token endpoint.
 *
 * **Output discipline.** Everything a human reads goes to stderr, including
 * the test root's id; the refresh token is the only thing on stdout, alone on
 * one line:
 *
 * ```
 * DRIVE_TOOL_CLIENT_SECRET=... ./gradlew -q :tools:drive-auth:driveAuth | pbcopy
 * ```
 */
fun main() = runTool("driveAuth") {
    val secret = DriveTooling.clientSecret()
    val challenge = Pkce.newChallenge()

    // 127.0.0.1 rather than localhost: Google documents the literal address,
    // and a resolver that maps localhost to ::1 would miss this listener.
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val redirectUri = "http://127.0.0.1:${server.address.port}"
    val arrived = CompletableFuture<String>()
    server.createContext("/") { exchange ->
        val query = exchange.requestURI.rawQuery.orEmpty()
        // Browsers ask for /favicon.ico too; only a request that carries an
        // OAuth answer settles the attempt.
        val answered = "code=" in query || "error=" in query
        val page = if (answered) "CloudLug: you can close this tab and return to the terminal." else ""
        val bytes = page.toByteArray()
        exchange.sendResponseHeaders(if (answered) 200 else 404, bytes.size.toLong().takeIf { it > 0 } ?: -1)
        exchange.responseBody.use { it.write(bytes) }
        if (answered) arrived.complete(query)
    }
    server.start()

    val code = try {
        System.err.println(
            """
            |CloudLug — Google Drive refresh token
            |
            |Open this URL in a browser on this machine, sign in with the scratch
            |account, and approve access to files CloudLug creates:
            |
            |  ${GoogleOAuth.authorizeUrl(DriveTooling.DESKTOP_CLIENT_ID, challenge, redirectUri)}
            |
            |Listening on $redirectUri for the redirect (5 minutes).
            |Nothing is written to disk.
            |
            """.trimMargin(),
        )
        val query = try {
            arrived.get(5, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            DriveTooling.fail("No redirect arrived within five minutes. Start again.")
        }
        when (val result = readRedirect(query, challenge)) {
            is Redirect.Code -> result.code
            is Redirect.Refused -> DriveTooling.fail("Google returned '${result.error}' instead of a code.")
            Redirect.StateMismatch -> DriveTooling.fail(
                "The redirect carried a state this attempt did not send (§8.4); its code was not used.",
            )
            Redirect.Empty -> DriveTooling.fail("The redirect carried neither a code nor an error.")
        }
    } finally {
        server.stop(0)
    }

    val client = OkHttpClient()
    val form = GoogleOAuth.codeExchangeForm(DriveTooling.DESKTOP_CLIENT_ID, code, challenge, redirectUri, secret)
    val grant = client.newCall(
        Request.Builder()
            .url(GoogleOAuth.TOKEN_ENDPOINT)
            .post(Pkce.formBody(form).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build(),
    ).execute().use { response ->
        val body = parseObject(response.body?.string().orEmpty())
        if (!response.isSuccessful) {
            // The tag, never the body (§26).
            DriveTooling.fail("Exchange failed: HTTP ${response.code}${body?.string("error")?.let { " ($it)" } ?: ""}.")
        }
        body ?: DriveTooling.fail("The token endpoint answered with something that is not JSON.")
    }

    val refreshToken = grant.string("refresh_token") ?: DriveTooling.fail(
        "Google returned no refresh token. That happens without access_type=offline and prompt=consent " +
            "— check the URL opened was the one printed above.",
    )
    val granted = GoogleOAuth.parseScopes(grant.string("scope"))
    if (GoogleOAuth.SCOPE_FILE !in granted) {
        System.err.println("Warning: drive.file was not granted. Every Drive tool will fail with 403.")
    }

    val accessToken = grant.string("access_token") ?: DriveTooling.fail("the grant carried no access_token")
    when (val root = findOrCreateTestRoot(DriveHttp(client, accessToken))) {
        is TestRoot.Found -> System.err.println("\nTest root found: DRIVE_TEST_ROOT=${root.id}")
        is TestRoot.Created -> System.err.println("\nTest root created in My Drive: DRIVE_TEST_ROOT=${root.id}")
        is TestRoot.Ambiguous -> System.err.println(
            "\n${root.ids.size} folders named ${DriveTooling.TEST_ROOT_NAME} are visible to this project " +
                "(Drive allows same-name siblings). Delete all but one and run again, or set " +
                "DRIVE_TEST_ROOT to one of: ${root.ids.joinToString(", ")}",
        )
    }

    System.err.println(
        "\nRefresh token follows on stdout. Store it as the DRIVE_REFRESH_TOKEN secret. " +
            "While the OAuth app is in Testing status it stops working in seven days.\n",
    )
    println(refreshToken)
}

internal sealed interface Redirect {
    data class Code(val code: String) : Redirect
    data class Refused(val error: String) : Redirect
    data object StateMismatch : Redirect
    data object Empty : Redirect
}

/**
 * §8.4 applies to a terminal too: a loopback port is reachable by anything on
 * this machine, so a redirect is only ours if it carries our state. The state
 * is checked before the code is even looked at.
 */
internal fun readRedirect(rawQuery: String, challenge: PkceChallenge): Redirect {
    val parameters = rawQuery.split('&').filter { '=' in it }.associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }
    if (!Pkce.statesMatch(challenge.state, parameters["state"])) return Redirect.StateMismatch
    parameters["error"]?.let { return Redirect.Refused(it) }
    return parameters["code"]?.let(Redirect::Code) ?: Redirect.Empty
}
