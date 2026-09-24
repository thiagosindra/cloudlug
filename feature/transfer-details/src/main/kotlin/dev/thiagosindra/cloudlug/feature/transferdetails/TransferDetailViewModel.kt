package dev.thiagosindra.cloudlug.feature.transferdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thiagosindra.cloudlug.auth.AccountRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.scheduling.TransferScheduler
import dev.thiagosindra.cloudlug.transfer.TransferController
import dev.thiagosindra.cloudlug.ui.TransferStage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailState(
    val transfer: TransferEntity? = null,
    val items: List<TransferItemEntity> = emptyList(),
    /** For §24.3's title, which names both ends by account at one provider. */
    val accountNames: Map<AccountId, String> = emptyMap(),
) {
    /** The item currently moving bytes, which §24.3 shows as CURRENT FILE. */
    val currentItem: TransferItemEntity?
        get() = items.firstOrNull { it.status.isActive }

    /**
     * Which leg of the §24.3 hop diagram to highlight.
     *
     * Driven by the current item's own status rather than by the transfer's,
     * because §13.2 says item state is the furthest stage reached — the transfer
     * being RUNNING says nothing about whether these particular bytes are on
     * their way down or up.
     */
    val stage: TransferStage
        get() = when (currentItem?.status) {
            TransferItemStatus.DOWNLOADING, TransferItemStatus.CHECKING_DESTINATION -> TransferStage.DOWNLOADING
            TransferItemStatus.CACHED, TransferItemStatus.UPLOADING -> TransferStage.UPLOADING
            TransferItemStatus.VERIFYING -> TransferStage.VERIFYING
            else -> TransferStage.IDLE
        }

    val canPause: Boolean get() = transfer?.status == TransferStatus.RUNNING
    val canResume: Boolean
        get() = transfer?.status == TransferStatus.PAUSED || transfer?.status == TransferStatus.READY
    val canCancel: Boolean get() = transfer?.status?.isTerminal == false
    val canRetry: Boolean get() = transfer?.status == TransferStatus.COMPLETED_WITH_ISSUES
}

/** Spec §24.3, and the §22 controls that act on the transfer while it runs. */
@HiltViewModel
class TransferDetailViewModel @Inject constructor(
    private val controller: TransferController,
    private val scheduler: TransferScheduler,
    accounts: AccountRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transferId = TransferId(checkNotNull(savedStateHandle["transferId"]))

    val state: StateFlow<DetailState> =
        combine(
            controller.observeTransfer(transferId),
            controller.observeItems(transferId),
            accounts.observe(),
        ) { transfer, items, connected ->
            DetailState(
                transfer = transfer,
                items = items,
                accountNames = connected.mapNotNull { account ->
                    (account.displayEmail ?: account.displayName)?.let { account.id to it }
                }.toMap(),
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    // Start, Resume and Retry all enqueue: the platform owns the running of a
    // transfer now (§17), and the controller is what the platform's job calls.
    fun start() = viewModelScope.launch { scheduler.enqueue(transferId) }

    fun pause() = viewModelScope.launch {
        // Cancel the job first: leaving it scheduled would have the platform
        // start the transfer again the moment the controller let go.
        scheduler.cancel(transferId)
        controller.pause(transferId)
    }

    fun resume() = viewModelScope.launch { scheduler.enqueue(transferId) }

    fun cancel() = viewModelScope.launch {
        scheduler.cancel(transferId)
        controller.cancel(transferId)
    }

    fun cancelItem(itemId: TransferItemId) =
        viewModelScope.launch { controller.cancelItem(transferId, itemId) }

    /** Spec §22.4: re-queue what did not finish, then keep going. */
    fun retryIncomplete() = viewModelScope.launch {
        controller.retryIncomplete(transferId)
        scheduler.enqueue(transferId)
    }
}
