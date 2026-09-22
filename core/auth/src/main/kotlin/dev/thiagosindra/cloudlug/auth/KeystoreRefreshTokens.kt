package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.model.AccountId
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
 *
 * One key per account as of v0.4. A Dropbox-to-Dropbox transfer holds two
 * accounts of the same provider at once, so a single key would have had the
 * destination's credential overwriting the source's at connect time.
 */
class KeystoreRefreshTokens(
    private val secrets: SecretStore,
) : DropboxRefreshTokenStore {

    override fun read(account: AccountId): String? =
        secrets.get(keyFor(account)) ?: adoptLegacyCredential(account)

    override fun write(account: AccountId, token: String) = secrets.put(keyFor(account), token)

    override fun clear(account: AccountId) {
        secrets.remove(keyFor(account))
        secrets.remove(LEGACY_KEY)
    }

    /**
     * Moves a credential written before keys carried an account id.
     *
     * Without this an upgrade is silently broken in the worst way: the account
     * row survives in Room, so §24.5 still says connected, while the
     * credential sits under a key nothing looks at any more. The first
     * transfer fails `AUTH_REQUIRED` and nothing on screen explains why.
     *
     * Safe precisely because of the limitation it is migrating away from — a
     * build that wrote this key could hold exactly one Dropbox account, so
     * there is no ambiguity about whose credential it is. It runs once: the
     * legacy key is removed as the value is rewritten under the new one.
     */
    private fun adoptLegacyCredential(account: AccountId): String? {
        val legacy = secrets.get(LEGACY_KEY) ?: return null
        secrets.put(keyFor(account), legacy)
        secrets.remove(LEGACY_KEY)
        return legacy
    }

    private fun keyFor(account: AccountId) = "$KEY_PREFIX${account.value}"

    private companion object {
        const val KEY_PREFIX = "dropbox.refresh-token."

        /** What v0.3 wrote, when one process could hold one Dropbox account. */
        const val LEGACY_KEY = "dropbox.refresh-token"
    }
}
