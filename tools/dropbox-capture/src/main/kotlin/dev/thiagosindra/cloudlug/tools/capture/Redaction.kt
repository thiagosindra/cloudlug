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
 * ### Session ids and cursors are replaced, and keep their shape
 *
 * An upload session id and a `list_folder` cursor are not credentials — neither
 * is usable without an access token for the account, and a session expires in
 * about a week — but both are opaque state derived from a real account, and a
 * public repository is not where they belong. They were once kept verbatim on
 * the reasoning that they are inert; "inert" and "not mine to publish" are
 * different questions, and only the second one matters here.
 *
 * Their pseudonyms keep the **original length and character set**, because a
 * fixture's job is to exercise the parser the way the captured body did: §22.5
 * reads an offset out of one of these, and a placeholder of some other shape
 * would test a body Dropbox never sends.
 *
 * ### What is deliberately *not* redacted
 *
 * `rev`, `content_hash` and timestamps are kept verbatim. None of them names a
 * person or a place, and the content hashes are what §21's verification tests
 * assert against — replacing them would leave a fixture that cannot test the
 * thing it was captured for. Names are ours already: this tool creates
 * everything it captures, so no filename in a fixture came from the account's
 * real contents.
 */
class Redaction(testRoot: String) {

    private val root = testRoot.trimEnd('/')
    private val assigned = LinkedHashMap<String, String>()
    private val sessions = LinkedHashMap<String, String>()
    private val cursors = LinkedHashMap<String, String>()

    /** How many distinct identifiers were replaced, for the run's summary. */
    val replacements: Int get() = assigned.size + sessions.size + cursors.size

    fun redact(body: String): String = body
        .replace(OBJECT_ID) { match -> "\"" + pseudonym(match.groupValues[1]) + "\"" }
        .replace(ACCOUNT_ID) { match -> "\"" + pseudonym(match.groupValues[1]) + "\"" }
        .replace(PATH_FIELD) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"" + rewritePath(match.groupValues[2]) + "\""
        }
        .replace(SESSION_FIELD) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"" + sameShape(sessions, "CLOUDLUGFIXTURESESSION", match.groupValues[2]) + "\""
        }
        .replace(CURSOR_FIELD) { match ->
            "\"cursor\":\"" + sameShape(cursors, "CLOUDLUGFIXTURECURSOR", match.groupValues[1]) + "\""
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

    /**
     * A stable pseudonym of exactly [real]'s length, keeping any `prefix:` the
     * value carries so the result still parses as what it replaced.
     *
     * Padded rather than truncated-and-hoped: a shorter replacement changes the
     * body's length, and a fixture whose length differs from what Dropbox sent
     * is no longer a record of what Dropbox sent.
     */
    private fun sameShape(into: MutableMap<String, String>, stem: String, real: String): String =
        into.getOrPut(real) {
            val prefix = if (':' in real) real.substringBefore(':') + ":" else ""
            val body = real.removePrefix(prefix)
            val named = "$stem%04d".format(into.size + 1)
            prefix + if (named.length >= body.length) named.take(body.length) else named.padEnd(body.length, 'A')
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

        /** Both keys Dropbox uses for a resumable upload session. */
        val SESSION_FIELD = Regex("\"(session_id|upload_session_id)\"\\s*:\\s*\"([^\"]*)\"")

        /** `list_folder` and its continuation both carry one. */
        val CURSOR_FIELD = Regex("\"cursor\"\\s*:\\s*\"([^\"]*)\"")
    }
}
