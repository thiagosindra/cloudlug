package dev.thiagosindra.cloudlug.tools.capture

/**
 * Removes the account's identifiers from a captured body without disturbing
 * anything else in it.
 *
 * ### Why this works on the raw text
 *
 * The value of a fixture is that it is what the service actually sent. Parsing
 * a body and re-serialising it would silently normalise whitespace and could
 * reorder or re-encode fields, so the file would record what kotlinx thinks
 * Dropbox said. Substituting inside the raw string keeps every other byte —
 * spacing, key order, escaping, the lot — exactly as it arrived.
 *
 * ### Why the pseudonyms are stable
 *
 * One capture run produces many files that refer to the same objects: a folder
 * appears in its parent's `list_folder`, again in its own, and again in the
 * metadata of the file inside it. Randomising per occurrence would break the
 * containment between them, and containment is precisely the property the
 * enumeration tests exist to check (ADR-0029). So every distinct real id maps
 * to one pseudonym for the whole run, and the map lives only in memory.
 *
 * ### What is deliberately *not* redacted
 *
 * `rev`, `content_hash`, `session_id` and timestamps are kept verbatim. None of
 * them names a person or a place: a revision and a session id are opaque and
 * already inert by the time a fixture is read, and the content hashes are what
 * §21's verification tests assert against — replacing them would leave a
 * fixture that cannot test the thing it was captured for. Names are ours
 * already: this tool creates everything it captures, so no filename in a
 * fixture came from the account's real contents.
 */
class Redaction(testRoot: String) {

    private val root = testRoot.trimEnd('/')
    private val assigned = LinkedHashMap<String, String>()

    /** How many distinct identifiers were replaced, for the run's summary. */
    val replacements: Int get() = assigned.size

    fun redact(body: String): String = body
        .replace(OBJECT_ID) { match -> "\"" + pseudonym(match.groupValues[1]) + "\"" }
        .replace(ACCOUNT_ID) { match -> "\"" + pseudonym(match.groupValues[1]) + "\"" }
        .replace(PATH_FIELD) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"" + rewritePath(match.groupValues[2]) + "\""
        }

    /**
     * A path under the capture root becomes the same path under `/fixtures`.
     *
     * Anything *not* under the root is replaced down to its last segment rather
     * than passed through. That case should not arise — the tool only touches
     * what it created — but a redactor whose failure mode is "emit the original"
     * is the wrong way round.
     */
    private fun rewritePath(raw: String): String {
        if (raw.isEmpty()) return raw
        val underRoot = raw.startsWith(root, ignoreCase = true)
        return if (underRoot) FIXTURE_ROOT + raw.substring(root.length) else "$FIXTURE_ROOT/" + raw.substringAfterLast('/')
    }

    private fun pseudonym(real: String): String = assigned.getOrPut(real) {
        val prefix = real.substringBefore(':')
        "$prefix:$PSEUDONYM_BODY%04d".format(assigned.size + 1)
    }

    private companion object {
        const val FIXTURE_ROOT = "/fixtures"
        const val PSEUDONYM_BODY = "CLOUDLUGFIXTURE"

        /** A Dropbox object id, always quoted in a body. */
        val OBJECT_ID = Regex("\"(id:[A-Za-z0-9_-]+)\"")

        /** An account id, which `list_folder` carries on shared entries. */
        val ACCOUNT_ID = Regex("\"(dbid:[A-Za-z0-9_-]+)\"")

        /** Both spellings of a path, with their values JSON-escaped. */
        val PATH_FIELD = Regex("\"(path_lower|path_display)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
