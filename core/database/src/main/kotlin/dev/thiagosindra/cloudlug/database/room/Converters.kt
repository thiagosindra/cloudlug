package dev.thiagosindra.cloudlug.database.room

import androidx.room.TypeConverter
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.time.Instant

/**
 * Column mappings for the types §12 stores that SQLite has no notion of.
 *
 * Room handles the `@JvmInline value class` identifiers and the enums itself.
 * Everything here is a type with structure: a path, a hash that carries its
 * algorithm, a timestamp, a scope set.
 *
 * Two rules these follow, because a converter is the one place a persistence
 * bug is invisible:
 *  - every conversion round-trips exactly, including the empty cases
 *    (`CloudPath.ROOT` is the empty string, an empty scope set is an empty
 *    string), which `RoomConverterTest` asserts value by value;
 *  - a malformed stored value throws rather than returning a default, because
 *    silently reading a corrupt row as a valid one is how a manifest starts
 *    disagreeing with the objects it describes (spec §2.4).
 */
object Converters {

    @TypeConverter
    fun pathToString(path: CloudPath?): String? = path?.toString()

    @TypeConverter
    fun stringToPath(value: String?): CloudPath? = value?.let(CloudPath::parse)

    /**
     * `algorithm:hexvalue`. The algorithm travels with the digest because §19.4
     * makes two hashes comparable only when their algorithms match — a bare hex
     * string would let a Dropbox `content_hash` be compared against a Drive MD5.
     */
    @TypeConverter
    fun hashToString(hash: ProviderHash?): String? = hash?.let { "${it.algorithm.id}:${it.value}" }

    @TypeConverter
    fun stringToHash(value: String?): ProviderHash? {
        if (value == null) return null
        val separator = value.indexOf(':')
        require(separator > 0) { "Stored hash '$value' has no algorithm prefix" }
        val id = value.substring(0, separator)
        val algorithm = HashAlgorithm.fromId(id)
            ?: throw IllegalArgumentException("Stored hash names unknown algorithm '$id'")
        return ProviderHash(algorithm, value.substring(separator + 1))
    }

    @TypeConverter
    fun checkpointToString(checkpoint: HashCheckpoint?): String? = checkpoint?.encoded

    @TypeConverter
    fun stringToCheckpoint(value: String?): HashCheckpoint? = value?.let(::HashCheckpoint)

    @TypeConverter
    fun instantToEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    /**
     * OAuth scopes as a newline-separated list. Newline rather than comma or
     * space because both appear inside scope strings in the wild, and §12.4
     * stores granted scopes verbatim so §8.2 can decide whether an account may
     * act as a source.
     */
    @TypeConverter
    fun scopesToString(scopes: Set<String>?): String? = scopes?.joinToString("\n")

    @TypeConverter
    fun stringToScopes(value: String?): Set<String>? =
        value?.let { if (it.isEmpty()) emptySet() else it.split("\n").toSet() }
}
