package dev.thiagosindra.cloudlug.app.di

import dev.thiagosindra.cloudlug.database.dao.CloudLugDatabase
import dev.thiagosindra.cloudlug.database.entity.AccountEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.ProviderType
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account rows for the demo providers, in debug builds only.
 *
 * §24.2 chooses **accounts** as of v0.4, not providers, so a provider with no
 * row in §12.4 cannot be picked at all. That is right for Google Drive, which
 * genuinely has no account — and wrong for the demo provider, whose whole
 * purpose is to be pickable without an account existing anywhere real.
 *
 * So the demo providers get rows. They are not pretending to be connected
 * accounts in any meaningful sense: no credential is stored for them, nothing
 * signed in, and §8.3's store is untouched. They exist so the wizard has
 * something to offer and the emulator journey test has something to click.
 *
 * Seeded rather than faked at read time because the wizard, the accounts
 * screen and the engine should all see one source of truth. A special case in
 * any one of them would be a lie the other two could disagree with.
 */
@Singleton
class DemoAccounts @Inject constructor(
    private val database: CloudLugDatabase,
    private val clock: Clock,
) {

    /**
     * Idempotent: `upsert` by a fixed id, so a relaunch does not accumulate
     * rows and a disconnect stays disconnected until the next cold start.
     */
    suspend fun seed() {
        demo.forEach { database.accounts.upsert(it) }
    }

    private val demo: List<AccountEntity>
        get() = listOf(
            account(ProviderType.FAKE, "demo-source", "Demo source", "demo-source@example.invalid"),
            account(ProviderType.GOOGLE_DRIVE, "demo-destination", "Demo destination", "demo-destination@example.invalid"),
        )

    private fun account(provider: ProviderType, id: String, name: String, email: String) = AccountEntity(
        id = AccountId(id),
        provider = provider,
        providerAccountId = id,
        displayName = name,
        displayEmail = email,
        // Deliberately empty. §7's scopes are what a real grant returned, and
        // these had no grant; the roles come from the adapter's declared
        // capabilities instead — see AccountRepository.rolesFor.
        grantedScopes = emptySet(),
        createdAt = clock.instant(),
    )
}
