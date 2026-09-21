package dev.thiagosindra.cloudlug.tools.auth

import dev.thiagosindra.cloudlug.provider.dropbox.DropboxOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * Mints a Dropbox refresh token for `DROPBOX_REFRESH_TOKEN`, without a
 * redirect listener or a browser round trip back into this process.
 *
 * Dropbox shows the authorization code on screen when no `redirect_uri` is
 * sent, so the whole flow is: open a URL, approve, paste a code. That is the
 * least machinery for something run once per scratch account.
 *
 * It reuses [DropboxOAuth] rather than reimplementing PKCE, which is the point:
 * the code path the app depends on is the one a person has driven by hand and
 * watched work. A second implementation here would be a second thing to be
 * wrong.
 *
 * **Output discipline.** Everything a human reads goes to stderr; the refresh
 * token is the only thing on stdout, alone on one line. So this works:
 *
 * ```
 * ./gradlew -q :tools:dropbox-auth:dropboxAuth | pbcopy
 * ```
 *
 * and the token can be pasted straight into the repository secret without ever
 * being scrolled past in a terminal. The access token is never printed at all —
 * it lives for four hours and the live gate mints its own.
 */
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val challenge = DropboxOAuth.newChallenge()

    System.err.println(
        """
        |CloudLug — Dropbox refresh token
        |
        |1. Open this URL and approve the scopes:
        |
        |   ${DropboxOAuth.authorizeUrl(challenge)}
        |
        |2. Dropbox will show an authorization code on screen.
        |3. Paste it here and press enter.
        |
        |Nothing is written to disk. The refresh token is printed once, on
        |stdout, and never stored by this tool.
        |
        """.trimMargin(),
    )
    System.err.print("Code: ")
    System.err.flush()

    val code = readlnOrNull()?.trim().orEmpty()
    if (code.isEmpty()) {
        System.err.println("No code entered; nothing to exchange.")
        exitProcess(2)
    }

    val form = DropboxOAuth.formBody(DropboxOAuth.codeExchangeForm(code, challenge))
    val request = HttpRequest.newBuilder(URI.create(DropboxOAuth.TOKEN_ENDPOINT))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()

    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        // The body of a failed grant carries an error tag, not a token. It is
        // still not echoed wholesale: an expired code is the common case and
        // the tag says so on its own.
        val tag = runCatching {
            json.parseToJsonElement(response.body()).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        System.err.println(
            "Exchange failed: HTTP ${response.statusCode()}${tag?.let { " ($it)" } ?: ""}. " +
                "An authorization code is single-use and expires quickly — start again from step 1.",
        )
        exitProcess(1)
    }

    val grant = json.parseToJsonElement(response.body()).jsonObject
    val refreshToken = grant["refresh_token"]?.jsonPrimitive?.content
    if (refreshToken.isNullOrBlank()) {
        System.err.println(
            "Dropbox returned no refresh token. That happens when token_access_type=offline " +
                "was not honoured — check the authorize URL was the one printed above.",
        )
        exitProcess(1)
    }

    // §7: what the user actually granted may be less than what was asked for.
    grant["scope"]?.jsonPrimitive?.content?.let { granted ->
        val missing = DropboxOAuth.SCOPES - granted.split(" ").toSet()
        if (missing.isNotEmpty()) {
            System.err.println(
                "Warning: these scopes were not granted: ${missing.joinToString(", ")}. " +
                    "The contract tests that need them will fail.",
            )
        }
    }

    System.err.println("\nRefresh token follows on stdout. Store it as the DROPBOX_REFRESH_TOKEN secret.\n")
    println(refreshToken)
}
