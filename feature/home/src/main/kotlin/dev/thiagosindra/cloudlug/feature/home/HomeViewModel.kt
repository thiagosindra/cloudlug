package dev.thiagosindra.cloudlug.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.transfer.TransferController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeState(
    val active: List<TransferEntity> = emptyList(),
    val history: List<TransferEntity> = emptyList(),
)

/**
 * Spec §24.1: active transfers above, finished ones below.
 *
 * The split is by terminal status rather than by "is a worker running", because
 * the database is authoritative (spec §2.4): a transfer interrupted by process
 * death is still active and still belongs at the top, where the user can resume
 * it.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: TransferController,
) : ViewModel() {

    val state: StateFlow<HomeState> = controller.observeTransfers()
        .map { transfers ->
            HomeState(
                active = transfers.filterNot { it.status.isTerminal },
                history = transfers.filter { it.status.isTerminal },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
