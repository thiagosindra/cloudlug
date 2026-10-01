package dev.thiagosindra.cloudlug.provider

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The PKCE material for one authorization attempt (RFC 7636).
 *
 * [verifier] is the secret. It never leaves the device and never appears in a
 * log (§26 redacts `code_verifier` by name); only [challenge] is sent with the
 * authorization request, and only [verifier] is sent with the exchange. That is
 * the whole point of PKCE: an attacker who intercepts the redirect gets a code
 * that is useless without a secret they never saw.
 *
 * [state] is unrelated to PKCE and exists for §8.4: the redirect that comes back
 * must carry the state this attempt sent, or it is not ours and the code in it
 * is not trusted.
 *
 * Callers must not assume a challenge can be reused: one attempt, one value.
 */
data class PkceChallenge(
    val verifier: String,
    val challenge: String,
    val state: String,
    val method: String = "S256",
)

/**
 * RFC 7636 for every provider.
 *
 * Here rather than in one adapter because Dropbox and Google Drive both run
 * it, and two copies of the verifier logic would be two things that could be
 * wrong independently — the reason the auth tools drive the app's own code
 * rather than their own.
 */
object Pkce {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * A fresh verifier, challenge and state.
     *
     * The verifier is 32 random bytes rendered base64url, which lands at 43
     * characters — the shortest RFC 7636 allows, and well inside its 128
     * ceiling. Base64url is used rather than picking from the unreserved set by
     * hand because a hand-rolled alphabet is where modulo bias creeps in.
     */
    fun newChallenge(random: SecureRandom = SecureRandom()): PkceChallenge {
        val verifier = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        return PkceChallenge(
            verifier = verifier,
            challenge = challengeFor(verifier),
            state = encoder.encodeToString(ByteArray(16).also(random::nextBytes)),
        )
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded — RFC 7636 §4.2. */
    fun challengeFor(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * §8.4: a redirect is only ours if it carries the state we sent.
     *
     * Constant-time so a mismatch cannot be probed by timing, which costs
     * nothing here and removes the need to think about it again.
     */
    fun statesMatch(expected: String, returned: String?): Boolean {
        if (returned == null || expected.length != returned.length) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), returned.toByteArray(Charsets.UTF_8))
    }

    /** Form-encodes one value the way both providers' endpoints expect. */
    fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    fun formBody(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
}
