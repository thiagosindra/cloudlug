package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.RefreshTokenStore
import dev.thiagosindra.cloudlug.security.SecretStore

/**
 * §8.3's store for one provider's refresh tokens, keyed by account.
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
 *
 * One instance per provider as of v0.6, each under its own key prefix, so a
 * Dropbox credential and a Google Drive credential can never share a key even
 * if their account ids collided.
 */
class KeystoreRefreshTokens(
    private val secrets: SecretStore,
    private val provider: ProviderType = ProviderType.DROPBOX,
) : RefreshTokenStore {

    override fun read(account: AccountId): String? =
        secrets.get(keyFor(account)) ?: if (provider == ProviderType.DROPBOX) adoptLegacyCredential(account) else null

    override fun write(account: AccountId, token: String) = secrets.put(keyFor(account), token)

    override fun clear(account: AccountId) {
        secrets.remove(keyFor(account))
        if (provider == ProviderType.DROPBOX) secrets.remove(LEGACY_KEY)
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

    private fun keyFor(account: AccountId) = "${prefixFor(provider)}${account.value}"

    private companion object {
        /** Dropbox's prefix is what v0.4 wrote, so existing credentials keep being found. */
        fun prefixFor(provider: ProviderType) = when (provider) {
            ProviderType.DROPBOX -> "dropbox.refresh-token."
            ProviderType.GOOGLE_DRIVE -> "google-drive.refresh-token."
            ProviderType.FAKE, ProviderType.FAKE_DESTINATION -> error("demo providers hold no credential")
        }

        /** What v0.3 wrote, when one process could hold one Dropbox account. */
        const val LEGACY_KEY = "dropbox.refresh-token"
    }
}
