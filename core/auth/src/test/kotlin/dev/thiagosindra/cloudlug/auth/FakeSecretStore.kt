package dev.thiagosindra.cloudlug.auth

import dev.thiagosindra.cloudlug.security.SecretStore

/**
 * The §8.3 contract without Keystore, so the logic above it is testable off a
 * device. What it is standing in for — that the bytes are encrypted at rest —
 * is asserted in `KeystoreSecretStoreTest`, on a real one.
 */
class FakeSecretStore : SecretStore {
    val entries = mutableMapOf<String, String>()

    override fun put(key: String, secret: String) { entries[key] = secret }
    override fun get(key: String): String? = entries[key]
    override fun remove(key: String) { entries.remove(key) }
    override fun clear() = entries.clear()
}
