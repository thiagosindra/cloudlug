package dev.thiagosindra.cloudlug.hashing

/**
 * Known-answer vectors (spec §31.1).
 *
 * The SHA-256 and MD5 values for the empty string and "abc" are the published
 * ones. Every other value was produced independently with Python's `hashlib`
 * from the algorithm definition in spec §19.4 — SHA-256 over the concatenated
 * SHA-256 digests of each 4 MiB block — rather than by running this
 * implementation, so these tests can fail.
 *
 * TODO(spec §36): before shipping the Dropbox adapter, also validate
 * `content_hash` against values reported by the live Dropbox API for real
 * uploads, which is the only check that catches a misreading of the spec that
 * both implementations share.
 */
object KnownVectors {
    const val BLOCK: Int = 4 * 1024 * 1024

    val EMPTY = Vector(
        bytes = ByteArray(0),
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        md5 = "d41d8cd98f00b204e9800998ecf8427e",
        contentHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    )

    val ABC = Vector(
        bytes = "abc".toByteArray(),
        sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        md5 = "900150983cd24fb0d6963f7d28e17f72",
        contentHash = "4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358",
    )

    /** Exactly one full block: the boundary case that a partial-block bug survives. */
    val ONE_FULL_BLOCK = Vector(
        bytes = ByteArray(BLOCK) { 'a'.code.toByte() },
        sha256 = "299285fc41a44cdb038b9fdaf494c76ca9d0c866672b2b266c1a0c17dda60a05",
        md5 = "bdbcf02ee0aa977795a79d25fcfdccb1",
        contentHash = "907a506cf5e706bda5c7a29b43c9c65d8344bd2fa2f22339b359c214812af5a1",
    )

    /** One full block plus a single byte: forces a second, one-byte block. */
    val BLOCK_PLUS_ONE = Vector(
        bytes = ByteArray(BLOCK + 1) { if (it < BLOCK) 'a'.code.toByte() else 'b'.code.toByte() },
        sha256 = "40835df3fcc2f431895f967af9e2048a88718f9db105c7b9e350df9f572a956d",
        md5 = "3782a54f026d83919a10212250a77cf0",
        contentHash = "565546ad93383e225e7cf808fb4d527a54dec54826a5c34a24c1f19a03c62583",
    )

    /** 9 MiB: two full blocks and a partial one, with non-repeating content. */
    val NINE_MIB = Vector(
        bytes = ByteArray(9 * 1024 * 1024) { ((it * 7 + 3) % 256).toByte() },
        sha256 = "5442747d5b57527d04199d7fae7335a74332192c51d3101fe01a913934719294",
        md5 = "909e741c85ed045d0f8047f83e892721",
        contentHash = "a15c6979e8384b5102e75e9c6fe9a2d6e5f798e58ef52bc1cb77dec9077f10d0",
    )

    val ALL = listOf(EMPTY, ABC, ONE_FULL_BLOCK, BLOCK_PLUS_ONE, NINE_MIB)

    class Vector(
        val bytes: ByteArray,
        val sha256: String,
        val md5: String,
        val contentHash: String,
    ) {
        override fun toString(): String = "${bytes.size} bytes"
    }
}
