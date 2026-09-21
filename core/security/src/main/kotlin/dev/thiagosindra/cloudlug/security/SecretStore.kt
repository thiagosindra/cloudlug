package dev.thiagosindra.cloudlug.security

/**
 * Where long-lived secrets live (spec §8.3).
 *
 * The contract is deliberately small — put, get, remove — because everything
 * interesting about §8.3 is a *prohibition*: secrets never reach Room, plain
 * preferences, logs, crash reports or manifests. A narrow surface is what makes
 * that auditable: there is one implementation, and anything holding a refresh
 * token goes through it.
 *
 * Access tokens are **not** stored here. §8.3 keeps them short-lived and in
 * memory, so a token that leaks with the process dies with it.
 */
interface SecretStore {

    /** Replaces any existing secret under [key]. */
    fun put(key: String, secret: String)

    /** Null when absent, or when the stored bytes can no longer be decrypted. */
    fun get(key: String): String?

    fun remove(key: String)

    /** Everything, for a wipe that must leave nothing behind. */
    fun clear()
}
