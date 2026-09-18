package dev.thiagosindra.cloudlug.transfer.manifest

import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Names the enclosing folder each transfer creates at the destination
 * (spec §10).
 *
 * The format is `CloudLug - <date> <time>`, using only characters legal on
 * every supported provider — no `: / \ < > " | ? *` — which is why the time uses
 * hyphens rather than colons.
 */
object EnclosingFolderNamer {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")

    /** Characters §10 excludes so one name works on every provider. */
    val UNIVERSALLY_ILLEGAL: Set<Char> = setOf(':', '/', '\\', '<', '>', '"', '|', '?', '*')

    fun nameFor(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "CloudLug - " + FORMAT.format(at.atZone(zone))

    /**
     * Returns a name not already present at the destination.
     *
     * Spec §10 requires that independent transfers not silently merge into the
     * same enclosing folder, and two transfers started in the same minute would
     * otherwise collide. A numeric suffix is appended until the name is free
     * (docs/decisions.md ADR-0012); this is the enclosing folder only, never an
     * item, which is still never auto-renamed (§19.3).
     */
    fun disambiguate(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var suffix = 2
        while ("$base ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }
}

/**
 * Whether a name can be represented at the destination (spec §20.4).
 *
 * Returns the [ItemStatusReason] to record, or null when the name is fine. v1
 * does not auto-rename: an unrepresentable name is a conflict the user resolves.
 *
 * Trailing spaces and dots and maximum path length are covered since v1.2 added
 * `disallowsTrailingSpaceOrDot` and `maxPathLength` to §5 capabilities, which is
 * what ADR-0013 was waiting for.
 */
object DestinationNameLegality {

    fun check(name: String, capabilities: ProviderCapabilities): ItemStatusReason? = when {
        name.isBlank() -> ItemStatusReason.CONFLICT_ILLEGAL_NAME
        name == "." || name == ".." -> ItemStatusReason.CONFLICT_ILLEGAL_NAME
        name.length > capabilities.maxNameLength -> ItemStatusReason.CONFLICT_ILLEGAL_NAME
        name.any { it in capabilities.illegalNameCharacters } -> ItemStatusReason.CONFLICT_ILLEGAL_NAME
        capabilities.disallowsTrailingSpaceOrDot && (name.endsWith(" ") || name.endsWith(".")) ->
            ItemStatusReason.CONFLICT_ILLEGAL_NAME

        else -> null
    }

    /**
     * Whether the whole destination-relative path fits (spec §5, §20.4).
     *
     * Checked separately from [check] because a path can exceed the limit while
     * every individual name is legal — the failure belongs to the item's
     * position in the tree, not to its name.
     */
    fun checkPath(path: CloudPath, capabilities: ProviderCapabilities): ItemStatusReason? {
        val limit = capabilities.maxPathLength ?: return null
        return if (path.toString().length > limit) ItemStatusReason.CONFLICT_ILLEGAL_NAME else null
    }
}
