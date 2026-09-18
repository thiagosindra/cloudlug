package dev.thiagosindra.cloudlug.provider

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType

/**
 * A connected account (spec §7).
 *
 * Only non-secret metadata lives here and in Room; credentials are held in
 * Keystore-protected storage (spec §8.3, §12.4). [grantedScopes] is what lets
 * the engine decide whether an account may act as source, destination or both
 * (spec §8.2, §9) — the same Drive adapter serves a `drive.file`-only build and
 * a self-built `drive.readonly` one without code changes.
 */
data class CloudAccount(
    val id: AccountId,
    val provider: ProviderType,
    val displayName: String?,
    val displayEmail: String?,
    val grantedScopes: Set<String>,
)
