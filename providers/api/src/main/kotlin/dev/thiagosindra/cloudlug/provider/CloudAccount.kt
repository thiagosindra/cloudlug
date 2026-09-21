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

/**
 * What an account may do, given what its user actually granted (spec §7, §9).
 *
 * Separate from [ProviderCapabilities] because the two answer different
 * questions. Capabilities are a property of the build — whether this APK asked
 * for a read scope at all — while this is a property of one grant: the same
 * build can hold one account the user granted everything and another where they
 * declined at the consent screen.
 */
data class AccountRoles(val canBeSource: Boolean, val canBeDestination: Boolean) {
    val canDoNothing: Boolean get() = !canBeSource && !canBeDestination
}
