package dev.thiagosindra.cloudlug.feature.transferdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.database.entity.TransferItemEntity
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferItemId
import dev.thiagosindra.cloudlug.model.TransferItemStatus
import dev.thiagosindra.cloudlug.model.TransferStatus
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transferId = TransferId(checkNotNull(savedStateHandle["transferId"]))

    val state: StateFlow<DetailState> =
        combine(
            controller.observeTransfer(transferId),
            controller.observeItems(transferId),
        ) { transfer, items -> DetailState(transfer, items) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun start() = viewModelScope.launch { controller.start(transferId) }

    fun pause() = viewModelScope.launch { controller.pause(transferId) }

    fun resume() = viewModelScope.launch { controller.resume(transferId) }

    fun cancel() = viewModelScope.launch { controller.cancel(transferId) }

    fun cancelItem(itemId: TransferItemId) =
        viewModelScope.launch { controller.cancelItem(transferId, itemId) }

    /** Spec §22.4: re-queue what did not finish, then keep going. */
    fun retryIncomplete() = viewModelScope.launch {
        controller.retryIncomplete(transferId)
        controller.start(transferId)
    }
}
