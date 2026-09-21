package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.provider.dropbox.DropboxRefreshTokenStore
import dev.thiagosindra.cloudlug.security.SecretStore

/**
 * §8.3's store, in the shape the Dropbox adapter asks for.
 *
 * The indirection earns its keep: the adapter's refresh logic — the part whose
 * failure mode is an account that stops working four hours later — is pure
 * Kotlin and tested on the JVM, while the bytes still land in Keystore-backed
 * storage on a device.
 *
 * [SecretStore.get] answers null for a credential it cannot decrypt, having
 * first said so at warning level, so a tampered or orphaned credential arrives
 * here as "not connected" and the user reconnects.
 */
class KeystoreRefreshTokens(
    private val secrets: SecretStore,
    private val key: String = DROPBOX_REFRESH_TOKEN,
) : DropboxRefreshTokenStore {

    override fun read(): String? = secrets.get(key)

    override fun write(token: String) = secrets.put(key, token)

    override fun clear() = secrets.remove(key)

    private companion object {
        /**
         * One Dropbox account for now.
         *
         * `AccountId`'s own documentation says multiple accounts per provider
         * are supported, and the engine is ready for it: every `CloudProvider`
         * method takes one. The gap is narrower than it looks and entirely in
         * this adapter — `DropboxTokenSource` has no account parameter, so one
         * process can hold one Dropbox credential. Recorded in docs/status.md
         * as a v0.4 item, because Google Drive will want the same fix and the
         * two are the same change.
         */
        const val DROPBOX_REFRESH_TOKEN = "dropbox.refresh-token"
    }
}
