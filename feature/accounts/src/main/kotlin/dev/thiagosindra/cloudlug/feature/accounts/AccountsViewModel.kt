package dev.thiagosindra.cloudlug.feature.accounts

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thiagosindra.cloudlug.auth.AccountRepository
import dev.thiagosindra.cloudlug.auth.AuthorizationCancelledException
import dev.thiagosindra.cloudlug.auth.NoBrowserAvailableException
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.transfer.pipeline.AvailableProviders
import dev.thiagosindra.cloudlug.ui.providerLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** One provider, connected or not (§24.5). */
data class AccountRow(
    val provider: ProviderType,
    val account: CloudAccount?,
    val roles: AccountRoles?,
    /** False for a provider this build cannot connect yet (Google Drive until v0.4). */
    val connectable: Boolean,
) {
    val connected: Boolean get() = account != null
}

/**
 * A disconnect that is waiting for the user to confirm.
 *
 * §8.3's disconnect is not undoable — the grant is revoked at the provider —
 * and [activeTransfers] is why that matters here rather than being a generic
 * "are you sure": a transfer already running against this account will stop
 * mid-file, and the user is the only one who can say whether that is what they
 * meant (ADR-0028).
 */
data class PendingDisconnect(
    val account: CloudAccount,
    val activeTransfers: Int,
)

/**
 * A consent page the screen should now open (§8.1).
 *
 * Built here rather than in the click handler because building it can fail —
 * AppAuth needs a browser, and there may not be one. A composable's `onClick`
 * is no place for that: an exception thrown there is uncaught and takes the app
 * down, which is what it did.
 *
 * [id] rather than the intent identifies the request, because `Intent` has no
 * equals and a launch must happen exactly once per press.
 */
data class AuthorizationLaunch(val id: Long, val provider: ProviderType, val intent: Intent)

data class AccountsState(
    val rows: List<AccountRow> = emptyList(),
    val working: Boolean = false,
    val pendingDisconnect: PendingDisconnect? = null,
    val launch: AuthorizationLaunch? = null,
    val message: String? = null,
)

/**
 * §24.5's accounts screen.
 *
 * Deliberately the minimal version ADR-0028 chose: the providers this build
 * supports, whether each is connected, what the grant allows, and one action
 * per row. No re-authorization prompts, no per-account settings, no quota — all
 * of those want a screen that has earned them.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val controller: TransferController,
    available: AvailableProviders,
) : ViewModel() {

    /**
     * The providers that can hold an account.
     *
     * The demo provider is in [AvailableProviders] for debug builds because the
     * wizard offers it, but it has no account, no sign-in and nothing to
     * revoke — a row for it could only ever say so.
     */
    private val supported = available.types.filterNot { it == ProviderType.FAKE }
    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val working: Boolean = false,
        val pendingDisconnect: PendingDisconnect? = null,
        val launch: AuthorizationLaunch? = null,
        val message: String? = null,
    )

    private val launches = AtomicLong()

    val state: StateFlow<AccountsState> = combine(accounts.observe(), local) { connected, local ->
        AccountsState(
            rows = supported.map { provider ->
                val account = connected.firstOrNull { it.provider == provider }
                AccountRow(
                    provider = provider,
                    account = account,
                    roles = account?.let(accounts::rolesFor),
                    connectable = accounts.canConnect(provider),
                )
            },
            working = local.working,
            pendingDisconnect = local.pendingDisconnect,
            launch = local.launch,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsState())

    /**
     * §8.1: asks for the consent page, and answers with a message when it
     * cannot be opened at all.
     *
     * The failure this exists for is a device with no browser. §8.1 requires a
     * browser rather than a WebView so CloudLug never sees the user's
     * password, so a missing one is a genuine dead end — but a dead end the
     * user can be told about.
     */
    fun requestConnect(provider: ProviderType) {
        local.value = try {
            local.value.copy(
                launch = AuthorizationLaunch(launches.incrementAndGet(), provider, accounts.authorizationIntent(provider)),
                message = null,
            )
        } catch (noBrowser: NoBrowserAvailableException) {
            local.value.copy(
                launch = null,
                message = "CloudLug signs in through your browser so it never sees your password. " +
                    "This device has no browser installed, so ${providerLabel(provider)} can't be connected here.",
            )
        }
    }

    /** The screen has launched the consent page; do not launch it twice. */
    fun onAuthorizationLaunched() {
        local.value = local.value.copy(launch = null)
    }

    fun onAuthorizationResult(provider: ProviderType, result: Intent?) {
        viewModelScope.launch {
            local.value = local.value.copy(working = true, message = null)
            local.value = try {
                accounts.completeConnection(provider, result)
                local.value.copy(working = false)
            } catch (cancelled: AuthorizationCancelledException) {
                // Backing out of a sign-in is not a failure and must not raise
                // a §24 error dialog. The user knows what they did.
                local.value.copy(working = false)
            } catch (failure: CloudException) {
                local.value.copy(working = false, message = connectFailureMessage(failure))
            }
        }
    }

    /**
     * §8.3 via ADR-0028: confirm first, and say what it will interrupt.
     *
     * The count is taken now rather than when the dialog opens, so a transfer
     * that started while the user was reading is still counted.
     */
    fun requestDisconnect(account: CloudAccount) {
        viewModelScope.launch {
            local.value = local.value.copy(
                pendingDisconnect = PendingDisconnect(account, activeTransfersUsing(account)),
                message = null,
            )
        }
    }

    fun dismissDisconnect() {
        local.value = local.value.copy(pendingDisconnect = null)
    }

    fun confirmDisconnect() {
        val pending = local.value.pendingDisconnect ?: return
        viewModelScope.launch {
            local.value = local.value.copy(working = true, pendingDisconnect = null)
            local.value = try {
                accounts.disconnect(pending.account.id)
                local.value.copy(working = false)
            } catch (failure: CloudException) {
                // The account is still connected: §8.3 revokes before it
                // forgets, so a failure here leaves something to try again.
                local.value.copy(working = false, message = disconnectFailureMessage(failure))
            }
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    /**
     * Unfinished transfers on either side of this account.
     *
     * Not "workers currently running": §2.4 makes the database authoritative,
     * so a transfer interrupted by process death is still this account's work
     * and disconnecting would still break it.
     */
    private suspend fun activeTransfersUsing(account: CloudAccount): Int =
        controller.observeTransfers().first().count { transfer ->
            !transfer.status.isTerminal &&
                (transfer.sourceAccountId == account.id || transfer.destinationAccountId == account.id)
        }

    private fun connectFailureMessage(failure: CloudException): String = when (failure.kind) {
        CloudErrorKind.TRANSIENT_NETWORK ->
            "Couldn't reach the provider. Check your connection and try again."
        CloudErrorKind.AUTH_REQUIRED -> "The sign-in didn't complete. Try connecting again."
        else -> "The account couldn't be connected."
    }

    private fun disconnectFailureMessage(failure: CloudException): String = when (failure.kind) {
        CloudErrorKind.TRANSIENT_NETWORK ->
            "Couldn't reach the provider to revoke access, so the account is still connected. Try again when you're online."
        else -> "The account couldn't be disconnected."
    }
}
