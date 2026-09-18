package dev.thiagosindra.cloudlug.model

/**
 * A relative, provider-neutral path inside a transfer (spec §6, §10).
 *
 * Paths are layout information, never identity: the engine addresses objects by
 * [dev.thiagosindra.cloudlug.model.ProviderType]-scoped opaque IDs and uses
 * [CloudPath] only to reproduce the source tree under the destination enclosing
 * folder. A [CloudPath] is always relative — it has no leading separator and no
 * provider root — and it never contains `.` or `..` segments, so it cannot be
 * used to escape the destination folder.
 */
class CloudPath private constructor(val segments: List<String>) {

    val isRoot: Boolean get() = segments.isEmpty()

    /** The last segment, or null for the root. */
    val name: String? get() = segments.lastOrNull()

    /** The containing path, or null for the root. */
    val parent: CloudPath?
        get() = if (isRoot) null else CloudPath(segments.dropLast(1))

    fun child(name: String): CloudPath {
        validateSegment(name)
        return CloudPath(segments + name)
    }

    fun resolve(other: CloudPath): CloudPath = CloudPath(segments + other.segments)

    /** Lower-cased form used to detect collisions on case-insensitive destinations (spec §20.3). */
    fun caseFoldedKey(): String = segments.joinToString("/") { it.lowercase() }

    override fun toString(): String = segments.joinToString("/")

    override fun equals(other: Any?): Boolean = other is CloudPath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        val ROOT: CloudPath = CloudPath(emptyList())

        fun of(vararg segments: String): CloudPath = parse(segments.joinToString("/"))

        /**
         * Parses a `/`-separated relative path. Leading, trailing and repeated
         * separators are ignored; `.` and `..` are rejected outright rather than
         * normalised away, because a manifest that contains them is a bug in an
         * adapter and silently rewriting it would hide that.
         */
        fun parse(raw: String): CloudPath {
            val parts = raw.split('/').filter { it.isNotEmpty() }
            parts.forEach(::validateSegment)
            return CloudPath(parts)
        }

        private fun validateSegment(segment: String) {
            require(segment.isNotEmpty()) { "Path segment must not be empty" }
            require(segment != "." && segment != "..") { "Relative path segment '$segment' is not allowed" }
            require(!segment.contains('/')) { "Path segment '$segment' must not contain a separator" }
        }
    }
}
