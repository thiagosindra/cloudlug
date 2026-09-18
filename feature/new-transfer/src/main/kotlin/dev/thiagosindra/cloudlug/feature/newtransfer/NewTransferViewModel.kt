package dev.thiagosindra.cloudlug.feature.newtransfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thiagosindra.cloudlug.database.TransferRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.transfer.manifest.EnclosingFolderNamer
import dev.thiagosindra.cloudlug.transfer.manifest.ManifestSummary
import dev.thiagosindra.cloudlug.transfer.pipeline.AvailableProviders
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

data class WizardState(
    val step: WizardStep = WizardStep.SOURCE,
    val availableProviders: List<ProviderType> = emptyList(),
    val source: ProviderType? = null,
    val destination: ProviderType? = null,
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
    availableProviders: AvailableProviders,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(WizardState(availableProviders = availableProviders.types))
    val state: StateFlow<WizardState> = _state.asStateFlow()

    fun chooseSource(type: ProviderType) {
        _state.update {
            it.copy(
                source = type,
                // Choosing a source can invalidate an already-picked destination.
                destination = it.destination?.takeIf { d -> d != type },
                step = WizardStep.DESTINATION,
            )
        }
    }

    fun chooseDestination(type: ProviderType) {
        _state.update { it.copy(destination = type, step = WizardStep.PICK_SOURCE) }
        browseSource()
    }

    private fun browseSource() = viewModelScope.launch {
        _state.update { it.copy(sourceChildren = childrenOfRoot(_state.value.source ?: return@launch)) }
    }

    fun toggleSourceSelection(objectId: String) {
        _state.update {
            val next = it.selectedSourceIds.toMutableSet()
            if (!next.add(objectId)) next.remove(objectId)
            it.copy(selectedSourceIds = next)
        }
    }

    fun toDestinationPicker() = viewModelScope.launch {
        val type = _state.value.destination ?: return@launch
        _state.update {
            it.copy(
                step = WizardStep.PICK_DESTINATION,
                destinationChildren = childrenOfRoot(type).filter { o -> o.type == CloudObjectType.FOLDER },
            )
        }
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
            val sourceProvider = providers.provider(source)
            val destinationProvider = providers.provider(destination)
            val sourceAccount = sourceProvider.authenticate()
            val destinationAccount = destinationProvider.authenticate()

            val transfer = repository.createTransfer(
                TransferEntity(
                    id = TransferId(UUID.randomUUID().toString()),
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                    sourceProvider = source,
                    sourceAccountId = sourceAccount.id,
                    destinationProvider = destination,
                    destinationAccountId = destinationAccount.id,
                    destinationRootId = destinationFolder,
                    destinationContainerName = EnclosingFolderNamer.nameFor(clock.instant()),
                    networkPolicy = current.networkPolicy,
                ),
            )

            val roots = current.sourceChildren.filter { it.id.opaqueId in current.selectedSourceIds }
            val summary = controller.prepare(
                transfer.id,
                CloudSelection.of(sourceAccount.id, roots),
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

    private suspend fun childrenOfRoot(type: ProviderType): List<CloudObject> {
        val provider = providers.provider(type)
        val account = provider.authenticate()
        return runCatching { provider.listChildren(account.id, rootOf(type)).toList() }
            .getOrElse { emptyList() }
    }

    private fun rootOf(type: ProviderType) = CloudObjectId(type, ROOT_ID)

    private companion object {
        const val ROOT_ID = "root"
    }
}
