package dev.thiagosindra.cloudlug.network

/**
 * Removes anything §26 forbids a log from carrying.
 *
 * §26 asks for "a single OkHttp interceptor plus a log-sink redactor rather
 * than per-call discipline", and the reason is that per-call discipline fails
 * silently: nobody notices the one call site that logged the whole response
 * body until a token is already on disk. Everything that can reach a log goes
 * through here, so the rule is enforced in one readable place.
 *
 * The approach is deny-by-default. Header values are dropped unless the header
 * is on a short allow-list of things that carry no secret and are worth having
 * when reading a trace; a new header added by a future adapter is redacted
 * until someone decides otherwise, which is the safe direction for a mistake.
 */
object Redaction {

    const val REDACTED = "<redacted>"

    /**
     * Headers whose values may be logged.
     *
     * `Authorization` is absent, and so is `Dropbox-API-Arg`: the latter is not
     * a credential but it carries file paths, and §26 prefers opaque
     * identifiers over filenames with debug logging of names opt-in.
     */
    private val LOGGABLE_HEADERS = setOf(
        "content-type",
        "content-length",
        "retry-after",
        "x-dropbox-request-id",
    )

    /**
     * Token-shaped values that appear in URLs and bodies.
     *
     * Query parameters are matched by name because a token's *value* has no
     * reliable shape — `sl.` prefixes, JWTs and opaque blobs all occur — and
     * matching a shape would miss the next format the provider invents.
     */
    private val SENSITIVE_QUERY_KEYS =
        setOf("access_token", "refresh_token", "code", "code_verifier", "client_secret", "id_token")

    private val JSON_SECRET = Regex(
        """"(access_token|refresh_token|code|code_verifier|client_secret|id_token)"\s*:\s*"[^"]*"""",
        RegexOption.IGNORE_CASE,
    )

    fun header(name: String, value: String): String =
        if (name.lowercase() in LOGGABLE_HEADERS) value else REDACTED

    /** Keeps the shape of a URL — scheme, host, path, parameter names — and drops secret values. */
    fun url(url: String): String {
        val separator = url.indexOf('?')
        if (separator < 0) return url
        val query = url.substring(separator + 1)
            .split('&')
            .filter { it.isNotEmpty() }
            .joinToString("&") { parameter ->
                val name = parameter.substringBefore('=')
                if (name.lowercase() in SENSITIVE_QUERY_KEYS) "$name=$REDACTED" else parameter
            }
        return url.substring(0, separator) + if (query.isEmpty()) "" else "?$query"
    }

    /**
     * A body is never logged whole — §26 forbids file contents outright, and a
     * token grant is a small JSON object that is *entirely* secret. This exists
     * for the error bodies §23 has to map, which the adapter reads deliberately
     * and which still must not carry a token if the provider echoes one.
     */
    fun body(body: String): String = JSON_SECRET.replace(body) { match ->
        """"${match.groupValues[1]}":"$REDACTED""""
    }
}
