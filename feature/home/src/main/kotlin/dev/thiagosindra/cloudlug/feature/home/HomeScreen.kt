package dev.thiagosindra.cloudlug.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thiagosindra.cloudlug.database.entity.TransferEntity
import dev.thiagosindra.cloudlug.model.TransferId
import dev.thiagosindra.cloudlug.ui.TransferProgress
import dev.thiagosindra.cloudlug.ui.bytesAreLowerBound
import dev.thiagosindra.cloudlug.ui.directionLabel
import dev.thiagosindra.cloudlug.ui.formatCount
import dev.thiagosindra.cloudlug.ui.progressFraction
import dev.thiagosindra.cloudlug.ui.summaryLine

// TopAppBar is still ExperimentalMaterial3Api. Opted into here rather than
// project-wide, so the next Compose bump shows exactly which screens the
// experimental surface reaches.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewTransfer: () -> Unit,
    onOpenTransfer: (TransferId) -> Unit,
    onAccounts: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CloudLug") },
                actions = {
                    // §24.5's way in. A text button rather than an icon: the
                    // word is unambiguous and this app has no icon set yet.
                    TextButton(onClick = onAccounts) { Text("Accounts") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTransfer,
                text = { Text("New Transfer") },
                icon = {},
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (state.active.isEmpty() && state.history.isEmpty()) {
                item {
                    Text(
                        "No transfers yet. Connect your cloud accounts, then start one to " +
                            "move files between them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.active.isNotEmpty()) {
                item { SectionHeading("Active") }
                items(state.active, key = { it.id.value }) { transfer ->
                    ActiveCard(transfer) { onOpenTransfer(transfer.id) }
                }
            }
            if (state.history.isNotEmpty()) {
                item { SectionHeading("History") }
                items(state.history, key = { it.id.value }) { transfer ->
                    HistoryCard(transfer) { onOpenTransfer(transfer.id) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ActiveCard(transfer: TransferEntity, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(transfer.directionLabel(), style = MaterialTheme.typography.titleSmall)
            Text(
                transfer.summaryLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TransferProgress(
                fraction = transfer.progressFraction(),
                completedFiles = transfer.completedFiles,
                totalFiles = transfer.totalFiles,
                completedBytes = transfer.completedBytes,
                totalBytes = transfer.totalBytes,
                bytesAreLowerBound = transfer.bytesAreLowerBound(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HistoryCard(transfer: TransferEntity, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(transfer.directionLabel(), style = MaterialTheme.typography.titleSmall)
            Text(
                "${transfer.summaryLine()} • ${formatCount(transfer.totalFiles)} files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
