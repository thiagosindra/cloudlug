package dev.thiagosindra.cloudlug.feature.newtransfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.auth.AccountRepository
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.transfer.manifest.EnclosingFolderNamer
import dev.thiagosindra.cloudlug.ui.providerLabel
import dev.thiagosindra.cloudlug.transfer.manifest.ManifestSummary
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** The six steps of spec §24.2. */
enum class WizardStep { SOURCE, DESTINATION, PICK_SOURCE, PICK_DESTINATION, REVIEW, STARTED }

/** A connected account and what §7's granted scopes let it do. */
data class ConnectedAccount(val account: CloudAccount, val roles: AccountRoles)

data class WizardState(
    val step: WizardStep = WizardStep.SOURCE,
    val accounts: List<ConnectedAccount> = emptyList(),
    val source: AccountId? = null,
    val destination: AccountId? = null,
    val sourceChildren: List<CloudObject> = emptyList(),
    val selectedSourceIds: Set<String> = emptySet(),
    val destinationChildren: List<CloudObject> = emptyList(),
    val destinationFolderId: String? = null,
    val networkPolicy: TransferNetworkPolicy = TransferNetworkPolicy.UNMETERED_ONLY,
    val summary: ManifestSummary? = null,
    val transferId: TransferId? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /**
     * §7: an account can be a source only if its grant permits reading.
     *
     * The two lists differ because the grants can. An account may appear in
     * both, one, or neither.
     */
    val sourceChoices: List<ConnectedAccount> get() = accounts.filter { it.roles.canBeSource }

    /**
     * §2.2 as amended: anything but the account already chosen as the source.
     * Two accounts at one provider are a legal pair; one with itself is not.
     */
    val destinationChoices: List<ConnectedAccount>
        get() = accounts.filter { it.roles.canBeDestination && it.account.id != source }

    /**
     * "Dropbox (a@example.com) -> Dropbox (b@example.com)", for §24.2's review.
     *
     * The provider alone stopped being enough the moment two accounts of one
     * provider could be the two ends of a transfer.
     */
    fun directionLabel(): String {
        fun name(id: AccountId?): String {
            val connected = accounts.firstOrNull { it.account.id == id } ?: return "?"
            val who = connected.account.displayEmail ?: connected.account.displayName
            return providerLabel(connected.account.provider) + (who?.let { " ($it)" } ?: "")
        }
        return "${name(source)} -> ${name(destination)}"
    }

    fun providerOf(account: AccountId): ProviderType? =
        accounts.firstOrNull { it.account.id == account }?.account?.provider

    val canContinue: Boolean
        get() = when (step) {
            WizardStep.SOURCE -> source != null
            WizardStep.DESTINATION -> destination != null
            WizardStep.PICK_SOURCE -> selectedSourceIds.isNotEmpty()
            WizardStep.PICK_DESTINATION -> destinationFolderId != null
            WizardStep.REVIEW -> summary != null && !busy
            WizardStep.STARTED -> false
        }
}

/**
 * Drives the §24.2 wizard.
 *
 * The picker here is the in-app browser §9 calls for, backed by the provider's
 * own enumeration API, so it works the same for the fake provider now and for
 * Dropbox in v0.3 without the engine noticing.
 */
@HiltViewModel
class NewTransferViewModel @Inject constructor(
    private val controller: TransferController,
    private val repository: TransferRepository,
    private val providers: ProviderRegistry,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    init {
        // §24.2 step 1 offers accounts, not providers: with two Dropbox
        // accounts connected, the provider is no longer the thing being
        // chosen. The list is observed rather than read once so that
        // connecting an account from §24.5 and coming back shows it.
        viewModelScope.launch {
            accounts.observe().collect { connected ->
                _state.update { state ->
                    state.copy(
                        accounts = connected.map { ConnectedAccount(it, accounts.rolesFor(it)) },
                        source = state.source?.takeIf { id -> connected.any { it.id == id } },
                        destination = state.destination?.takeIf { id -> connected.any { it.id == id } },
                    )
                }
            }
        }
    }

    fun chooseSource(account: AccountId) {
        _state.update {
            it.copy(
                source = account,
                // Choosing a source can invalidate an already-picked destination.
                destination = it.destination?.takeIf { d -> d != account },
                step = WizardStep.DESTINATION,
            )
        }
    }

    fun chooseDestination(account: AccountId) {
        _state.update { it.copy(destination = account, step = WizardStep.PICK_SOURCE) }
        browseSource()
    }

    private fun browseSource() = viewModelScope.launch {
        val account = _state.value.source ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        runCatching { childrenOfRoot(account) }
            .onSuccess { children -> _state.update { it.copy(sourceChildren = children, busy = false) } }
            .onFailure { failure -> _state.update { it.copy(busy = false, error = browseFailure(account, failure)) } }
    }

    fun toggleSourceSelection(objectId: String) {
        _state.update {
            val next = it.selectedSourceIds.toMutableSet()
            if (!next.add(objectId)) next.remove(objectId)
            it.copy(selectedSourceIds = next)
        }
    }

    fun toDestinationPicker() = viewModelScope.launch {
        val account = _state.value.destination ?: return@launch
        _state.update { it.copy(step = WizardStep.PICK_DESTINATION, busy = true, error = null) }
        runCatching { childrenOfRoot(account).filter { it.type == CloudObjectType.FOLDER } }
            .onSuccess { folders -> _state.update { it.copy(destinationChildren = folders, busy = false) } }
            .onFailure { failure -> _state.update { it.copy(busy = false, error = browseFailure(account, failure)) } }
    }

    fun chooseDestinationFolder(objectId: String) {
        _state.update { it.copy(destinationFolderId = objectId) }
    }

    fun setNetworkPolicy(policy: TransferNetworkPolicy) {
        _state.update { it.copy(networkPolicy = policy) }
    }

    /**
     * Step 5: create the transfer and build the manifest so the review can show
     * counts, sizes and what will be skipped (§24.2, §20).
     *
     * This leaves the transfer READY. It creates no folder at the destination —
     * §10 defers that to `READY -> RUNNING`, precisely so that a user who
     * reviews this screen and backs out leaves nothing behind.
     */
    fun review() = viewModelScope.launch {
        val current = _state.value
        val source = current.source ?: return@launch
        val destination = current.destination ?: return@launch
        val destinationFolder = current.destinationFolderId ?: return@launch
        _state.update { it.copy(busy = true, error = null) }

        runCatching {
            // No authenticate() calls here any more. Both accounts were chosen
            // in steps 1 and 2 and are already in §12.4; asking the provider
            // who it is would cost a round trip and, with two accounts of one
            // provider connected, could not have said which.
            val sourceType = current.providerOf(source)
                ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "the source account is no longer connected")
            val destinationType = current.providerOf(destination)
                ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "the destination account is no longer connected")

            val transfer = repository.createTransfer(
                TransferEntity(
                    id = TransferId(UUID.randomUUID().toString()),
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                    sourceProvider = sourceType,
                    sourceAccountId = source,
                    destinationProvider = destinationType,
                    destinationAccountId = destination,
                    destinationRootId = destinationFolder,
                    destinationContainerName = EnclosingFolderNamer.nameFor(clock.instant()),
                    networkPolicy = current.networkPolicy,
                ),
            )

            val roots = current.sourceChildren.filter { it.id.opaqueId in current.selectedSourceIds }
            val summary = controller.prepare(
                transfer.id,
                CloudSelection.of(source, roots),
            )
            transfer.id to summary
        }.onSuccess { (id, summary) ->
            _state.update {
                it.copy(step = WizardStep.REVIEW, transferId = id, summary = summary, busy = false)
            }
        }.onFailure { failure ->
            _state.update { it.copy(busy = false, error = failure.message ?: "Could not prepare the transfer") }
        }
    }

    /** Step 6. */
    fun start() = viewModelScope.launch {
        val id = _state.value.transferId ?: return@launch
        controller.start(id)
        _state.update { it.copy(step = WizardStep.STARTED) }
    }

    fun back() {
        _state.update {
            it.copy(
                step = when (it.step) {
                    WizardStep.DESTINATION -> WizardStep.SOURCE
                    WizardStep.PICK_SOURCE -> WizardStep.DESTINATION
                    WizardStep.PICK_DESTINATION -> WizardStep.PICK_SOURCE
                    WizardStep.REVIEW -> WizardStep.PICK_DESTINATION
                    else -> it.step
                },
                error = null,
            )
        }
    }

    /**
     * Asks the provider where its root is rather than guessing (ADR-0026).
     *
     * A failure here is not caught: an empty picker and a provider that threw
     * look identical to the user, and this screen spent v0.2 reporting the
     * second as the first. The callers above turn it into a visible message.
     */
    /**
     * Lists one account's root.
     *
     * No longer calls `authenticate()` to discover an account id: the account
     * was chosen in step 1 or 2 and is already recorded in §12.4. That call
     * cost a network round trip per step and, with two accounts of one
     * provider connected, could not have said which of them this is.
     */
    private suspend fun childrenOfRoot(account: AccountId): List<CloudObject> {
        val type = _state.value.providerOf(account)
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "that account is no longer connected")
        val provider = providers.provider(type)
        return provider.listChildren(account, provider.rootOf(account)).toList()
    }

    private fun browseFailure(account: AccountId, failure: Throwable): String = when {
        // The one failure with an obvious next step. Without this the user sees
        // the adapter's own wording, which explains the state but not the cure.
        failure is CloudException && failure.kind == CloudErrorKind.AUTH_REQUIRED ->
            "That account needs reconnecting, on the Accounts screen."

        else -> {
            val provider = _state.value.providerOf(account)?.let(::providerLabel) ?: "that account"
            failure.message ?: "Could not list the contents of $provider"
        }
    }
}
