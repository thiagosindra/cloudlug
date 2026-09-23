package dev.thiagosindra.cloudlug.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thiagosindra.cloudlug.auth.AccountRepository
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.transfer.TransferController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeState(
    val active: List<TransferEntity> = emptyList(),
    val history: List<TransferEntity> = emptyList(),
    /**
     * What to call each account, for §24.1's rows. Needed because two accounts
     * at one provider make "Dropbox -> Dropbox" the same string for every
     * transfer between them, in either direction.
     */
    val accountNames: Map<AccountId, String> = emptyMap(),
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
    accounts: AccountRepository,
) : ViewModel() {

    val state: StateFlow<HomeState> = combine(
        controller.observeTransfers(),
        accounts.observe(),
    ) { transfers, connected ->
        HomeState(
            active = transfers.filterNot { it.status.isTerminal },
            history = transfers.filter { it.status.isTerminal },
            // A disconnected account drops out of this map and the label falls
            // back to the id, which is right: §8.3 removes the row, but the
            // transfers it ran stay in the history below.
            accountNames = connected.mapNotNull { account ->
                (account.displayEmail ?: account.displayName)?.let { account.id to it }
            }.toMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
