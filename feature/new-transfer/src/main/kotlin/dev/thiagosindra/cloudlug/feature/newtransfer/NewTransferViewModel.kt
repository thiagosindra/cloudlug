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
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.provider.CloudSelection
import dev.thiagosindra.cloudlug.provider.SelectionRoot
import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.scheduling.TransferScheduler
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
    /** Where the provider says this account's tree begins (ADR-0026). */
    val sourceAccountRoot: CloudObjectId? = null,
    /** The folders descended into, outermost first; empty at the account root. */
    val sourcePath: List<CloudObject> = emptyList(),
    val sourceChildren: List<CloudObject> = emptyList(),
    /**
     * Selected roots by object id, each carrying the display path §10 will
     * reproduce at the destination.
     *
     * Keyed across the whole browse, not per level: §9 has the user descend one
     * level at a time, and a selection that evaporated on the way down would
     * make choosing two folders in different places impossible. Insertion
     * order is kept, so the review step lists them in the order they were
     * picked.
     */
    val selectedSources: Map<String, SelectionRoot> = emptyMap(),
    val destinationAccountRoot: CloudObjectId? = null,
    val destinationPath: List<CloudObject> = emptyList(),
    val destinationChildren: List<CloudObject> = emptyList(),
    val destinationFolderId: String? = null,
    /** The chosen destination folder as the user saw it, for §24.2's review. */
    val destinationFolderLabel: String? = null,
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

    /** Where the browser is, as a path a person can read. */
    val sourceLocation: String get() = locationOf(sourcePath)

    val destinationLocation: String get() = locationOf(destinationPath)

    /**
     * The object whose children are on screen: the deepest folder descended
     * into, or the account root before any descent.
     */
    val destinationHere: CloudObjectId? get() = destinationPath.lastOrNull()?.id ?: destinationAccountRoot

    /**
     * Where [obj] lands under the enclosing folder (§10).
     *
     * `CloudObject` carries identity, not a path (§6), so nothing below the UI
     * can work this out. The browser knows it because it walked here, which is
     * the whole reason `SelectionRoot` carries the path as data rather than the
     * engine resolving it (ADR-0014, overruled).
     */
    fun displayPathOf(obj: CloudObject): CloudPath =
        sourcePath.fold(CloudPath.ROOT) { path, ancestor -> path.child(ancestor.name) }.child(obj.name)

    private fun locationOf(path: List<CloudObject>): String =
        if (path.isEmpty()) "/" else path.joinToString("/", prefix = "/") { it.name }

    fun providerOf(account: AccountId): ProviderType? =
        accounts.firstOrNull { it.account.id == account }?.account?.provider

    val canContinue: Boolean
        get() = when (step) {
            WizardStep.SOURCE -> source != null
            WizardStep.DESTINATION -> destination != null
            WizardStep.PICK_SOURCE -> selectedSources.isNotEmpty()
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
    private val scheduler: TransferScheduler,
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
        browseSource(emptyList())
    }

    // ------------------------------------------------------- §9's browser
    //
    // Both pickers descend. Before this, every row toggled selection and
    // nothing opened a folder, so only the account's top level could be
    // transferred at all — §9 describes a browser built on listChildren one
    // level at a time, and a browser that cannot descend is a list.

    /** Lists [path]'s deepest folder, or the account root when it is empty. */
    private fun browseSource(path: List<CloudObject>) = viewModelScope.launch {
        val account = _state.value.source ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        runCatching {
            val provider = providerFor(account)
            val root = provider.rootOf(account)
            root to provider.listChildren(account, path.lastOrNull()?.id ?: root).toList()
        }.onSuccess { (root, children) ->
            _state.update {
                it.copy(
                    sourceAccountRoot = root,
                    sourcePath = path,
                    sourceChildren = children,
                    busy = false,
                )
            }
        }.onFailure { failure ->
            _state.update { it.copy(busy = false, error = browseFailure(account, failure)) }
        }
    }

    fun openSourceFolder(folder: CloudObject) {
        if (folder.type != CloudObjectType.FOLDER) return
        browseSource(_state.value.sourcePath + folder)
    }

    fun sourceUp() {
        val path = _state.value.sourcePath
        if (path.isEmpty()) return
        browseSource(path.dropLast(1))
    }

    /**
     * Adds or removes [obj] as a root, recording where it was found.
     *
     * The display path is captured here rather than at `review()` because here
     * is where it is known: by the time the user is on the review step the
     * browser may be somewhere else entirely, and the object's ancestors are
     * not recoverable from the object (§6).
     */
    fun toggleSourceSelection(obj: CloudObject) {
        _state.update { state ->
            val next = state.selectedSources.toMutableMap()
            val key = obj.id.opaqueId
            if (next.remove(key) == null) next[key] = SelectionRoot(obj, state.displayPathOf(obj))
            state.copy(selectedSources = next)
        }
    }

    fun toDestinationPicker() {
        _state.update { it.copy(step = WizardStep.PICK_DESTINATION) }
        browseDestination(emptyList())
    }

    private fun browseDestination(path: List<CloudObject>) = viewModelScope.launch {
        val account = _state.value.destination ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        runCatching {
            val provider = providerFor(account)
            val root = provider.rootOf(account)
            // Only folders: §10 puts the enclosing folder inside whatever is
            // chosen here, so a file is not a place a transfer can land.
            root to provider.listChildren(account, path.lastOrNull()?.id ?: root)
                .toList()
                .filter { it.type == CloudObjectType.FOLDER }
        }.onSuccess { (root, folders) ->
            _state.update {
                it.copy(
                    destinationAccountRoot = root,
                    destinationPath = path,
                    destinationChildren = folders,
                    busy = false,
                )
            }
        }.onFailure { failure ->
            _state.update { it.copy(busy = false, error = browseFailure(account, failure)) }
        }
    }

    fun openDestinationFolder(folder: CloudObject) {
        if (folder.type != CloudObjectType.FOLDER) return
        browseDestination(_state.value.destinationPath + folder)
    }

    fun destinationUp() {
        val path = _state.value.destinationPath
        if (path.isEmpty()) return
        browseDestination(path.dropLast(1))
    }

    /**
     * Chooses the folder currently open, rather than one selected in the list.
     *
     * A destination is one place, and the row that names it is also the row
     * that opens it — a control that meant "select" would leave no way to look
     * inside before committing to it. Choosing the level you are standing on
     * removes the ambiguity, and makes the account root choosable, which it
     * has to be: §10 creates its own enclosing folder inside whatever this is.
     */
    fun chooseCurrentDestinationFolder() {
        _state.update {
            val here = it.destinationHere ?: return@update it
            it.copy(destinationFolderId = here.opaqueId, destinationFolderLabel = it.destinationLocation)
        }
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

        // Held outside the block so a failure can take back the row the block
        // created: §11 needs somewhere to write the manifest before it knows
        // whether there is one, so the transfer necessarily exists first.
        var created: TransferId? = null

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
            created = transfer.id

            // The selection itself, with the display paths the browser
            // recorded. `CloudSelection.of` is the flat-picker helper: it names
            // every root by its own name alone, which would land
            // photos/2025/July at the destination as plain "July" and lose the
            // ancestors §10 preserves.
            val summary = controller.prepare(
                transfer.id,
                CloudSelection(source, current.selectedSources.values.toList()),
            )
            transfer.id to summary
        }.onSuccess { (id, summary) ->
            _state.update {
                it.copy(step = WizardStep.REVIEW, transferId = id, summary = summary, busy = false)
            }
        }.onFailure { failure ->
            // The user is still standing in the wizard and is about to read the
            // error, so the half-made transfer has no one to belong to. Left
            // alone it sits on §24.1 reporting "Preparing, 0 / 0 files" with no
            // process behind it and no action that applies to it. §24.2 step 5
            // wants a review the user backs out of to leave nothing behind, and
            // one that never got as far as a manifest is the same thing.
            created?.let { id -> runCatching { repository.discardTransfer(id) } }
            _state.update { it.copy(busy = false, error = failure.message ?: "Could not prepare the transfer") }
        }
    }

    /**
     * Step 6: hand the transfer to the platform (§17).
     *
     * Not `controller.start`, which runs in an app-scoped coroutine and dies
     * with the process. Going through the scheduler means the path a user takes
     * is the path that ships — and the emulator journey exercises it, rather
     * than a second one that nothing tests.
     */
    fun start() = viewModelScope.launch {
        val id = _state.value.transferId ?: return@launch
        scheduler.enqueue(id)
        _state.update { it.copy(step = WizardStep.STARTED) }
    }

    fun back() {
        // Inside a folder, back means up — the same thing the Up control does.
        // Leaving the step is what back means only at the top of the tree,
        // which is where the user began.
        val current = _state.value
        if (current.step == WizardStep.PICK_SOURCE && current.sourcePath.isNotEmpty()) {
            sourceUp()
            return
        }
        if (current.step == WizardStep.PICK_DESTINATION && current.destinationPath.isNotEmpty()) {
            destinationUp()
            return
        }

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
     * The adapter serving [account], or a failure the caller turns into a
     * visible message.
     *
     * A failure here is deliberately not swallowed: an empty picker and a
     * provider that threw look identical to the user, and this screen spent
     * v0.2 reporting the second as the first.
     *
     * No `authenticate()` call: the account was chosen in step 1 or 2 and is
     * already recorded in §12.4. That call cost a round trip per step and,
     * with two accounts of one provider connected, could not have said which
     * of them this is.
     */
    private fun providerFor(account: AccountId): CloudProvider {
        val type = _state.value.providerOf(account)
            ?: throw CloudException(CloudErrorKind.AUTH_REQUIRED, "that account is no longer connected")
        return providers.provider(type)
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
