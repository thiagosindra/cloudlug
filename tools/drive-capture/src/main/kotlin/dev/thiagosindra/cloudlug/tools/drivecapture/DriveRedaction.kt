package dev.thiagosindra.cloudlug.tools.drivecapture

/**
 * Removes the account's identifiers from captured Drive responses without
 * disturbing anything else in them.
 *
 * The same principles as `:tools:dropbox-capture`'s `Redaction`, which this
 * follows deliberately:
 *
 * - **Raw text, not re-serialised JSON**, so every other byte is what Drive
 *   sent.
 * - **One pseudonym per real value for the whole run**, so a folder's id in
 *   its own `files.get` is the same string as in its child's `parents` —
 *   containment is what the enumeration and lookup tests check (ADR-0029).
 * - **Same length and character set** for opaque values the adapter parses:
 *   file ids, `upload_id`s in session URIs, and `nextPageToken`s.
 *
 * ### Where Drive differs from Dropbox
 *
 * Drive ids carry no prefix to find them by, and they turn up outside id
 * fields — in error messages ("File not found: …") and inside the session URI
 * a resumable upload returns in `Location`. So ids are **harvested** from the
 * fields that define them (`id`, `parents`, `permissionId`, `nextPageToken`,
 * `upload_id`) and then replaced **everywhere** they occur, in bodies and in
 * the recorded headers alike. A capture run redacts only after it has seen
 * every response, so an id first defined late in the run is still replaced in
 * a body recorded early.
 *
 * `about.get` names the account: `emailAddress`, `displayName` and
 * `photoLink` are replaced with invented values. They are not parsed for
 * shape, so they do not keep their length.
 *
 * ### What is deliberately *not* redacted
 *
 * `md5Checksum`, `sha256Checksum`, `size`, timestamps and `version` are kept:
 * none names a person or a place, and the checksums are what §21's
 * verification asserts against. Names are ours: the capture tool creates
 * everything it records.
 *
 * ### The failure mode is refusal
 *
 * [leaks] lists every real value that survived redaction, and any email
 * address outside the reserved example domains, and anything shaped like a
 * Google token. The capture tool writes
 * nothing when it is non-empty.
 */
class DriveRedaction {

    private val ids = LinkedHashMap<String, String>()
    private val personal = LinkedHashMap<String, String>()

    /** How many distinct values were replaced, for the run's summary. */
    val replacements: Int get() = ids.size + personal.size

    /** Registers an opaque value that must never reach a fixture. */
    fun register(real: String) {
        if (real.length < MIN_ID_LENGTH || real in ids) return
        ids[real] = sameShape(real, ids.size + 1)
    }

    /** Collects every identifier [body] defines. Call on every body and header before [redact]. */
    fun harvest(text: String) {
        ID_FIELD.findAll(text).forEach { register(it.groupValues[2]) }
        PARENTS.findAll(text).forEach { match -> QUOTED.findAll(match.groupValues[1]).forEach { register(it.groupValues[1]) } }
        UPLOAD_ID.findAll(text).forEach { register(it.groupValues[1]) }
        PERSONAL_FIELD.findAll(text).forEach { match ->
            val value = match.groupValues[3]
            if (value.isNotEmpty()) personal[value] = PERSONAL_REPLACEMENTS.getValue(match.groupValues[1])
        }
    }

    fun redact(text: String): String {
        var out = PERSONAL_FIELD.replace(text) { match ->
            "\"${match.groupValues[1]}\"${match.groupValues[2]}\"${PERSONAL_REPLACEMENTS.getValue(match.groupValues[1])}\""
        }
        // Longest first, so an id that happens to contain a shorter one is
        // replaced whole rather than partially rewritten.
        ids.keys.sortedByDescending { it.length }.forEach { real -> out = out.replace(real, ids.getValue(real)) }
        return out
    }

    /** Every real value still present in [text]. Empty is the only acceptable answer. */
    fun leaks(text: String): List<String> {
        val survived = (ids.keys + personal.keys).filter { it in text }.map { "a registered value of length ${it.length}" }
        val emails = EMAIL.findAll(text).map { it.value }.filterNot { email ->
            SAFE_EMAIL_DOMAINS.any { email.endsWith("@$it") }
        }.map { "an email address" }
        val credentials = CREDENTIAL.findAll(text).map { "something shaped like a Google token" }
        return survived + emails + credentials
    }

    /**
     * A stable pseudonym of exactly [real]'s length, in Drive's id alphabet.
     * Padded with `x` rather than truncated-and-hoped: a fixture whose values
     * differ in length from what Drive sent is no longer a record of it.
     */
    private fun sameShape(real: String, ordinal: Int): String {
        val stem = "FIXTURE%04d".format(ordinal)
        return if (stem.length >= real.length) stem.takeLast(real.length) else stem.padEnd(real.length, 'x')
    }

    private companion object {
        /** Shorter values are not ids, and replacing them would corrupt ordinary text. */
        const val MIN_ID_LENGTH = 10

        val ID_FIELD = Regex("\"(id|permissionId|nextPageToken|driveId)\"\\s*:\\s*\"([^\"]+)\"")
        val PARENTS = Regex("\"parents\"\\s*:\\s*\\[([^\\]]*)]")
        val QUOTED = Regex("\"([^\"]+)\"")
        val UPLOAD_ID = Regex("[?&]upload_id=([A-Za-z0-9_.-]+)")
        val PERSONAL_FIELD = Regex("\"(emailAddress|displayName|photoLink)\"(\\s*:\\s*)\"([^\"]*)\"")
        val PERSONAL_REPLACEMENTS = mapOf(
            "emailAddress" to "scratch@example.com",
            "displayName" to "Fixture Account",
            "photoLink" to "https://example.invalid/photo",
        )
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        /** Google access tokens begin `ya29.`, refresh tokens `1//`. Neither may be redacted into a fixture; both refuse it. */
        val CREDENTIAL = Regex("ya29\\.[A-Za-z0-9_-]{10,}|1//[A-Za-z0-9_-]{20,}")
        val SAFE_EMAIL_DOMAINS = listOf("example.com", "example.invalid", "example.test")
    }
}
