package dev.thiagosindra.cloudlug.feature.newtransfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.ui.SingleLine
import dev.thiagosindra.cloudlug.ui.formatBytes
import dev.thiagosindra.cloudlug.ui.formatCount
import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.ui.providerLabel

// TopAppBar is still ExperimentalMaterial3Api. Opted into here rather than
// project-wide, so the next Compose bump shows exactly which screens the
// experimental surface reaches.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransferScreen(
    onBack: () -> Unit,
    onStarted: (TransferId) -> Unit,
    viewModel: NewTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.step, state.transferId) {
        if (state.step == WizardStep.STARTED) state.transferId?.let(onStarted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Transfer") },
                navigationIcon = {
                    IconButton(onClick = { if (state.step == WizardStep.SOURCE) onBack() else viewModel.back() }) {
                        Text("<")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                when (state.step) {
                    WizardStep.SOURCE -> "1. Choose the source account"
                    WizardStep.DESTINATION -> "2. Choose the destination account"
                    WizardStep.PICK_SOURCE -> "3. Choose what to transfer"
                    WizardStep.PICK_DESTINATION -> "4. Choose the destination folder"
                    WizardStep.REVIEW -> "5. Review"
                    WizardStep.STARTED -> "Started"
                },
                style = MaterialTheme.typography.titleMedium,
            )

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Column(Modifier.weight(1f)) {
                when (state.step) {
                    // §7 decides which accounts each step offers: reading and
                    // writing are separate grants, so the two lists can differ.
                    WizardStep.SOURCE -> AccountList(
                        accounts = state.sourceChoices,
                        selected = state.source,
                        empty = "No connected account can be a source. Connect one on the " +
                            "Accounts screen, and accept the read permissions.",
                        onSelect = viewModel::chooseSource,
                    )

                    // §2.2 as amended: the source account is excluded, not its
                    // provider. A second Dropbox account is a legal destination.
                    WizardStep.DESTINATION -> AccountList(
                        accounts = state.destinationChoices,
                        selected = state.destination,
                        empty = "No other connected account can be a destination. Connect a " +
                            "second account on the Accounts screen.",
                        onSelect = viewModel::chooseDestination,
                    )

                    WizardStep.PICK_SOURCE -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (state.sourceChildren.isEmpty()) {
                            item { EmptyNotice(state, "There is nothing in this account to transfer.") }
                        }
                        items(state.sourceChildren, key = { it.id.opaqueId }) { obj ->
                            ListItem(
                                // The whole row toggles, not just the checkbox:
                                // a 24dp target beside a full-width row is the
                                // wrong thing to aim at on a phone.
                                modifier = Modifier.clickable {
                                    viewModel.toggleSourceSelection(obj.id.opaqueId)
                                },
                                headlineContent = { SingleLine(obj.name) },
                                supportingContent = {
                                    Text(
                                        when (obj.type) {
                                            CloudObjectType.FOLDER -> "Folder"
                                            CloudObjectType.PROVIDER_NATIVE_DOCUMENT -> "Native document"
                                            CloudObjectType.SHORTCUT -> "Shortcut"
                                            CloudObjectType.FILE -> obj.size?.let(::formatBytes) ?: "File"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                leadingContent = {
                                    Checkbox(
                                        checked = obj.id.opaqueId in state.selectedSourceIds,
                                        onCheckedChange = { viewModel.toggleSourceSelection(obj.id.opaqueId) },
                                    )
                                },
                            )
                        }
                    }

                    WizardStep.PICK_DESTINATION -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (state.destinationChildren.isEmpty()) {
                            item { EmptyNotice(state, "This account has no folder to transfer into.") }
                        }
                        items(state.destinationChildren, key = { it.id.opaqueId }) { obj ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    viewModel.chooseDestinationFolder(obj.id.opaqueId)
                                },
                                headlineContent = { SingleLine(obj.name) },
                                leadingContent = {
                                    RadioButton(
                                        selected = state.destinationFolderId == obj.id.opaqueId,
                                        onClick = { viewModel.chooseDestinationFolder(obj.id.opaqueId) },
                                    )
                                },
                            )
                        }
                    }

                    WizardStep.REVIEW -> Review(state, viewModel)
                    WizardStep.STARTED -> Text("Transfer started.")
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                when (state.step) {
                    WizardStep.PICK_SOURCE -> Button(
                        onClick = { viewModel.toDestinationPicker() },
                        enabled = state.canContinue,
                    ) { Text("Next") }

                    WizardStep.PICK_DESTINATION -> Button(
                        onClick = { viewModel.review() },
                        enabled = state.canContinue,
                    ) { Text("Review") }

                    WizardStep.REVIEW -> Button(
                        onClick = { viewModel.start() },
                        enabled = state.canContinue,
                    ) { Text("Start transfer") }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun AccountList(
    accounts: List<ConnectedAccount>,
    selected: AccountId?,
    empty: String,
    onSelect: (AccountId) -> Unit,
) {
    if (accounts.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(accounts, key = { it.account.id.value }) { connected ->
            val account = connected.account
            ListItem(
                modifier = Modifier.clickable { onSelect(account.id) },
                // The provider leads, because it is what distinguishes the
                // rows when only one account of each is connected — and the
                // address below distinguishes them when two of one are.
                headlineContent = { Text(providerLabel(account.provider)) },
                supportingContent = {
                    Text(
                        account.displayEmail ?: account.displayName ?: account.id.value,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    RadioButton(selected = selected == account.id, onClick = { onSelect(account.id) })
                },
            )
        }
    }
}

/**
 * A list that draws nothing looks the same whether it is loading, empty or
 * broken. That ambiguity is how ADR-0026's empty root survived to a device:
 * the picker had no files and said nothing about it.
 */
@Composable
private fun EmptyNotice(state: WizardState, message: String) {
    Text(
        text = when {
            state.busy -> "Loading\u2026"
            state.error != null -> "Nothing to show."
            else -> message
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
}

/** Step 5: everything §24.2 asks the user to confirm before any byte moves. */
@Composable
private fun Review(state: WizardState, viewModel: NewTransferViewModel) {
    val summary = state.summary ?: return
    Column {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                // Named by account, not just by provider: with two Dropbox
                // accounts connected, "Dropbox -> Dropbox" would not say which
                // way round this transfer goes.
                Text(state.directionLabel(), style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReviewRow("Files", formatCount(summary.files))
                ReviewRow("Folders", formatCount(summary.folders))
                ReviewRow(
                    "Total size",
                    if (summary.bytesAreLowerBound) {
                        "at least ${formatBytes(summary.bytes)}"
                    } else {
                        formatBytes(summary.bytes)
                    },
                )
                if (summary.unknownSizes > 0) {
                    ReviewRow("Size not known yet", formatCount(summary.unknownSizes))
                }
                if (summary.unsupported > 0) ReviewRow("Will be skipped", formatCount(summary.unsupported))
                if (summary.conflicts > 0) ReviewRow("In conflict", formatCount(summary.conflicts))
            }
        }

        Text("Network", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Row(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.networkPolicy == TransferNetworkPolicy.UNMETERED_ONLY,
                onClick = { viewModel.setNetworkPolicy(TransferNetworkPolicy.UNMETERED_ONLY) },
                label = { Text("Wi-Fi only") },
            )
            FilterChip(
                selected = state.networkPolicy == TransferNetworkPolicy.ANY_NETWORK,
                onClick = { viewModel.setNetworkPolicy(TransferNetworkPolicy.ANY_NETWORK) },
                label = { Text("Any network") },
            )
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
