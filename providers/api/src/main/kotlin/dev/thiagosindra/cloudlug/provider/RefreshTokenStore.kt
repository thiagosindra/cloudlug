package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId

/**
 * Where one provider's long-lived refresh tokens live, keyed by account (§8.3).
 *
 * Provider-neutral since v0.6, when Google Drive became the second provider to
 * need one: the v0.4 gap was that only Dropbox keyed credentials by
 * [AccountId]. Each provider gets its own store instance, so a Dropbox account
 * and a Drive account can never be filed under the same key even if their ids
 * collided.
 *
 * Deliberately no Android types: the real store is Keystore-backed and exists
 * only on a device, and putting it in this signature would make the refresh
 * logic testable only on an emulator.
 *
 * Callers must not assume [read] distinguishes "never stored" from "stored but
 * unreadable": §8.3 treats an undecryptable credential as absent, and the
 * account reconnects either way.
 */
interface RefreshTokenStore {
    fun read(account: AccountId): String?
    fun write(account: AccountId, token: String)
    fun clear(account: AccountId)
}
