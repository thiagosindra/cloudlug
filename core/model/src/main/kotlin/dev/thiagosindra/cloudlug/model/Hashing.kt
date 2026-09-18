package dev.thiagosindra.cloudlug.model

/**
 * A content hash algorithm CloudLug can compute locally while bytes stream
 * through the device (spec §19.4).
 *
 * The engine picks the destination's [ProviderCapabilities.nativeHashAlgorithm]
 * so that verification after upload is a metadata comparison rather than a
 * re-download (spec §21).
 */
enum class HashAlgorithm(
    /** Lower-case identifier persisted alongside a hash value. */
    val id: String,
) {
    SHA256("sha256"),
    SHA1("sha1"),
    MD5("md5"),

    /**
     * SHA-256 of the concatenated SHA-256 digests of each 4 MiB block, which is
     * what Dropbox calls `content_hash`. It is named after the provider because
     * that is the only place the algorithm is specified, but nothing in the
     * engine treats it specially — it is selected through capabilities like any
     * other algorithm.
     */
    DROPBOX_CONTENT_HASH("dropbox_content_hash"),
    ;

    companion object {
        fun fromId(id: String): HashAlgorithm? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A hash value reported by a provider or computed locally.
 *
 * [value] is the lower-case hex encoding of the digest. Two hashes are
 * comparable only when their [algorithm]s match — Dropbox's `content_hash` and
 * Drive's MD5 say nothing about each other (spec §19.4).
 */
data class ProviderHash(
    val algorithm: HashAlgorithm,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Hash value must not be blank" }
        require(value.all { it in HEX_DIGITS }) { "Hash value must be lower-case hex" }
    }

    /**
     * True when both hashes use the same algorithm and the same digest. Used by
     * the collision algorithm (spec §19.3) and by verification (spec §21);
     * hashes with different algorithms are *not* evidence of difference, only an
     * absence of evidence, so callers must distinguish [comparableTo] from this.
     */
    fun matches(other: ProviderHash): Boolean = comparableTo(other) && value == other.value

    fun comparableTo(other: ProviderHash): Boolean = algorithm == other.algorithm

    companion object {
        private val HEX_DIGITS = ('0'..'9') + ('a'..'f')

        fun of(algorithm: HashAlgorithm, bytes: ByteArray): ProviderHash =
            ProviderHash(algorithm, bytes.joinToString("") { "%02x".format(it) })
    }
}
