package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.provider.dropbox.PkceChallenge
import dev.thiagosindra.cloudlug.security.SecretStore

/**
 * The PKCE material for an authorization attempt, kept until the redirect
 * comes back.
 *
 * It has to survive process death. A Custom Tab puts CloudLug in the
 * background while the user signs in, possibly types a password, possibly
 * completes a second factor — on a phone under memory pressure that is ample
 * time to be killed, and a verifier held in a field would be gone. The user
 * would return to a redirect that cannot be exchanged, and the failure would
 * look random because it depends on what else the phone was doing.
 *
 * It belongs in [SecretStore] rather than preferences because the verifier is a
 * credential for the length of the flow: anyone holding it and an intercepted
 * code can complete the exchange, which is the exact attack PKCE prevents.
 *
 * [take] is destructive. A verifier is good for one exchange, and leaving a
 * spent one behind is a credential kept past its purpose (§8.3).
 */
class PendingAuthorization(
    private val secrets: SecretStore,
    private val key: String = PENDING,
) {

    fun remember(challenge: PkceChallenge) {
        secrets.put(key, listOf(challenge.verifier, challenge.challenge, challenge.state, challenge.method).joinToString(SEPARATOR))
    }

    /** The attempt in progress, removed as it is read; null when there is none. */
    fun take(): PkceChallenge? {
        val stored = secrets.get(key) ?: return null
        secrets.remove(key)

        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts.any { it.isEmpty() }) return null
        return PkceChallenge(verifier = parts[0], challenge = parts[1], state = parts[2], method = parts[3])
    }

    fun discard() = secrets.remove(key)

    private companion object {
        const val PENDING = "dropbox.pending-authorization"

        /**
         * Not in base64url's alphabet, which is what every field here is
         * rendered in, so no value can smuggle a separator into the next field.
         */
        const val SEPARATOR = ":"
    }
}
