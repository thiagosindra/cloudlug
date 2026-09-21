package dev.thiagosindra.cloudlug.security

import android.util.Log

/**
 * Told when a stored credential could not be read back.
 *
 * [SecretStore.get] answers null for an unreadable secret, because §8.3's
 * recovery is to reconnect and a crash on a path users reach by restoring a
 * phone would be worse than useless. But null alone erases the difference
 * between "there was never a credential here" and "there was one and it is now
 * unreadable", and those want different attention: the first is ordinary, the
 * second is either a destroyed Keystore key or someone editing the app's files.
 */
fun interface SecretStoreListener {

    /**
     * [key] is the storage key — `"dropbox"` — never the secret, and [reason]
     * names the failure class, never its contents (§26).
     */
    fun onUnreadableSecret(key: String, reason: UnreadableReason)

    companion object {
        /** Warning level: recoverable by reconnecting, but never routine. */
        val Logging = SecretStoreListener { key, reason ->
            Log.w("CloudLugSecrets", "credential '$key' is unreadable: ${reason.description}")
        }
    }
}

/**
 * Why a credential could not be decrypted.
 *
 * The two are worth telling apart. [KeystoreKeyMissing] is the system taking
 * the key away — a factory reset, a restored backup, an OEM wiping keys when
 * the lock screen changes — and the ciphertext left behind is inert. Anything
 * else means the key is present and the bytes beside it did not authenticate,
 * which is what tampering looks like.
 *
 * Distinguishing them takes care: a naive reader asks for the key, silently
 * generates a fresh one when it is absent, and then reports the resulting
 * authentication failure as if the data had been edited.
 */
sealed interface UnreadableReason {
    val description: String

    /** The Keystore key is gone; nothing can decrypt what is stored. */
    data object KeystoreKeyMissing : UnreadableReason {
        override val description = "the Keystore key no longer exists"
    }

    /** The key is present and the ciphertext did not authenticate. */
    data class DecryptionFailed(val failure: String) : UnreadableReason {
        override val description = "decryption failed ($failure)"
    }

    /** The stored value is not the shape this store writes. */
    data object Malformed : UnreadableReason {
        override val description = "the stored value is malformed"
    }
}
