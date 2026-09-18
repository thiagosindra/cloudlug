package dev.thiagosindra.cloudlug.hashing

import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.HashCheckpoint
import dev.thiagosindra.cloudlug.model.ProviderHash
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sin

/**
 * Hand-written SHA-256, SHA-1 and MD5.
 *
 * Spec §19.4 requires hashers "with serializable state rather than using
 * `MessageDigest` directly", because a `MessageDigest` cannot be snapshotted:
 * the JDK exposes no way to read or restore its internal chaining variables.
 * Every algorithm CloudLug needs is Merkle-Damgard, so all three share one
 * skeleton — chaining state, a sub-block buffer and a byte count — and differ
 * only in their compression function, word endianness and digest width.
 *
 * These are not general-purpose cryptographic primitives and are not used for
 * anything security-bearing; they are content fingerprints (spec §19.1). They
 * are validated against the published test vectors in `KnownVectors` and,
 * additionally, against the JDK's own `MessageDigest` over random inputs.
 */
internal abstract class MerkleDamgardHasher(
    final override val algorithm: HashAlgorithm,
    protected val state: IntArray,
) : StreamingHasher {

    private val buffer = ByteArray(BLOCK_BYTES)
    private var bufferLen = 0
    private var finished = false

    final override var bytesHashed: Long = 0
        private set

    /** Compresses one 64-byte block at [offset] into [state]. */
    protected abstract fun compress(state: IntArray, block: ByteArray, offset: Int)

    /** True when message words and the length suffix are little-endian (MD5). */
    protected abstract val littleEndian: Boolean

    /** Number of leading state words in the digest. */
    protected abstract val digestWords: Int

    final override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "$algorithm hasher already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "update($offset, $length) is outside a ${bytes.size}-byte buffer"
        }
        var consumed = 0
        // Top up a partial buffer first, then compress whole blocks straight
        // out of the caller's array so a large write copies nothing.
        if (bufferLen > 0) {
            val take = minOf(BLOCK_BYTES - bufferLen, length)
            bytes.copyInto(buffer, bufferLen, offset, offset + take)
            bufferLen += take
            consumed += take
            if (bufferLen == BLOCK_BYTES) {
                compress(state, buffer, 0)
                bufferLen = 0
            }
        }
        while (length - consumed >= BLOCK_BYTES) {
            compress(state, bytes, offset + consumed)
            consumed += BLOCK_BYTES
        }
        if (consumed < length) {
            val rest = length - consumed
            bytes.copyInto(buffer, 0, offset + consumed, offset + consumed + rest)
            bufferLen = rest
        }
        bytesHashed += length
    }

    final override fun digest(): ProviderHash {
        check(!finished) { "$algorithm hasher already finished" }
        // Pad a copy so that a hasher can be digested without destroying state
        // a caller may still checkpoint.
        val padded = state.copyOf()
        val tail = ByteArray(BLOCK_BYTES * 2)
        buffer.copyInto(tail, 0, 0, bufferLen)
        tail[bufferLen] = 0x80.toByte()
        val tailBlocks = if (bufferLen < BLOCK_BYTES - 8) 1 else 2
        val bitLength = bytesHashed * 8
        val lengthAt = tailBlocks * BLOCK_BYTES - 8
        if (littleEndian) {
            for (i in 0 until 8) tail[lengthAt + i] = (bitLength ushr (8 * i)).toByte()
        } else {
            for (i in 0 until 8) tail[lengthAt + i] = (bitLength ushr (8 * (7 - i))).toByte()
        }
        for (b in 0 until tailBlocks) compress(padded, tail, b * BLOCK_BYTES)

        val out = ByteArray(digestWords * 4)
        for (w in 0 until digestWords) {
            val word = padded[w]
            if (littleEndian) {
                for (i in 0 until 4) out[w * 4 + i] = (word ushr (8 * i)).toByte()
            } else {
                for (i in 0 until 4) out[w * 4 + i] = (word ushr (8 * (3 - i))).toByte()
            }
        }
        finished = true
        return ProviderHash.of(algorithm, out)
    }

    /** Writes chaining state, pending bytes and the counter (spec §19.4). */
    internal fun encodeTo(out: DataOutputStream) {
        out.writeLong(bytesHashed)
        out.writeByte(state.size)
        for (word in state) out.writeInt(word)
        out.writeByte(bufferLen)
        out.write(buffer, 0, bufferLen)
    }

    internal fun decodeFrom(input: DataInputStream) {
        bytesHashed = input.readLong()
        val words = input.readUnsignedByte()
        require(words == state.size) { "Checkpoint has $words state words, $algorithm has ${state.size}" }
        for (i in state.indices) state[i] = input.readInt()
        bufferLen = input.readUnsignedByte()
        require(bufferLen < BLOCK_BYTES) { "Checkpoint buffer length $bufferLen is not a partial block" }
        input.readFully(buffer, 0, bufferLen)
    }

    final override fun checkpoint(): HashCheckpoint = encodeCheckpoint(algorithm) { out ->
        out.writeByte(KIND_MERKLE_DAMGARD)
        encodeTo(out)
    }

    internal companion object {
        const val BLOCK_BYTES = 64
    }
}

private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

/** Reads a big-endian 32-bit word. */
private fun beWord(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 24) or
        ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or
        (b[at + 3].toInt() and 0xFF)

/** Reads a little-endian 32-bit word. */
private fun leWord(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or
        ((b[at + 3].toInt() and 0xFF) shl 24)

internal class Sha256Hasher : MerkleDamgardHasher(HashAlgorithm.SHA256, INITIAL.copyOf()) {

    override val littleEndian: Boolean get() = false
    override val digestWords: Int get() = 8

    private val w = IntArray(64)

    override fun compress(state: IntArray, block: ByteArray, offset: Int) {
        for (t in 0 until 16) w[t] = beWord(block, offset + t * 4)
        for (t in 16 until 64) {
            val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
            val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (t in 0 until 64) {
            val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + bigS1 + ch + K[t] + w[t]
            val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = bigS0 + maj
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private companion object {
        val INITIAL = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val K = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b,
            0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039,
            -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d,
            -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8,
            -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
        )
    }
}

internal class Sha1Hasher : MerkleDamgardHasher(HashAlgorithm.SHA1, INITIAL.copyOf()) {

    override val littleEndian: Boolean get() = false
    override val digestWords: Int get() = 5

    private val w = IntArray(80)

    override fun compress(state: IntArray, block: ByteArray, offset: Int) {
        for (t in 0 until 16) w[t] = beWord(block, offset + t * 4)
        for (t in 16 until 80) w[t] = rotl(w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16], 1)
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]; var e = state[4]
        for (t in 0 until 80) {
            val f: Int
            val k: Int
            when {
                t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5a827999 }
                t < 40 -> { f = b xor c xor d; k = 0x6ed9eba1 }
                t < 60 -> { f = (b and c) or (b and d) or (c and d); k = -0x70e44324 }
                else -> { f = b xor c xor d; k = -0x359d3e2a }
            }
            val temp = rotl(a, 5) + f + e + k + w[t]
            e = d; d = c; c = rotl(b, 30); b = a; a = temp
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d; state[4] += e
    }

    private companion object {
        val INITIAL = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476, -0x3c2d1e10)
    }
}

internal class Md5Hasher : MerkleDamgardHasher(HashAlgorithm.MD5, INITIAL.copyOf()) {

    override val littleEndian: Boolean get() = true
    override val digestWords: Int get() = 4

    private val m = IntArray(16)

    override fun compress(state: IntArray, block: ByteArray, offset: Int) {
        for (t in 0 until 16) m[t] = leWord(block, offset + t * 4)
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when {
                i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) and 15 }
                i < 48 -> { f = b xor c xor d; g = (3 * i + 5) and 15 }
                else -> { f = c xor (b or d.inv()); g = (7 * i) and 15 }
            }
            val rotated = rotl(a + f + K[i] + m[g], S[i])
            a = d; d = c; c = b; b = b + rotated
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
    }

    private companion object {
        val INITIAL = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)

        /** K[i] = floor(abs(sin(i + 1)) * 2^32), as RFC 1321 defines it. */
        val K = IntArray(64) { (abs(sin(it + 1.0)) * 4294967296.0).toLong().toInt() }

        val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )
    }
}
