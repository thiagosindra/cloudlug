package dev.thiagosindra.cloudlug.feature.transferdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thiagosindra.cloudlug.ui.ItemStatusGlyph
import dev.thiagosindra.cloudlug.ui.ProviderHopIndicator
import dev.thiagosindra.cloudlug.ui.SingleLine
import dev.thiagosindra.cloudlug.ui.TransferProgress
import dev.thiagosindra.cloudlug.ui.bytesAreLowerBound
import dev.thiagosindra.cloudlug.ui.directionLabel
import dev.thiagosindra.cloudlug.ui.formatBytes
import dev.thiagosindra.cloudlug.ui.progressFraction
import dev.thiagosindra.cloudlug.ui.providerLabel
import dev.thiagosindra.cloudlug.ui.summaryLine

// TopAppBar is still ExperimentalMaterial3Api. Opted into here rather than
// project-wide, so the next Compose bump shows exactly which screens the
// experimental surface reaches.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferDetailScreen(
    onBack: () -> Unit,
    viewModel: TransferDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transfer = state.transfer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(transfer?.directionLabel(state.accountNames) ?: "Transfer") },
                navigationIcon = { IconButton(onClick = onBack) { Text("<") } },
            )
        },
    ) { padding ->
        if (transfer == null) {
            Text("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                TransferProgress(
                    fraction = transfer.progressFraction(),
                    completedFiles = transfer.completedFiles,
                    totalFiles = transfer.totalFiles,
                    completedBytes = transfer.completedBytes,
                    totalBytes = transfer.totalBytes,
                    bytesAreLowerBound = transfer.bytesAreLowerBound(),
                )
                Text(
                    transfer.summaryLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item { OutcomeCounts(state) }

            state.currentItem?.let { current ->
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "CURRENT FILE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SingleLine(current.filename, style = MaterialTheme.typography.titleSmall)
                            current.size?.let { size ->
                                val moved = maxOf(current.downloadedBytes, current.uploadedBytes)
                                Text(
                                    "${formatBytes(moved)} / ${formatBytes(size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            ProviderHopIndicator(
                                sourceLabel = providerLabel(transfer.sourceProvider),
                                destinationLabel = providerLabel(transfer.destinationProvider),
                                stage = state.stage,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            OutlinedButton(
                                onClick = { viewModel.cancelItem(current.id) },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Cancel File") }
                        }
                    }
                }
            }

            item { Controls(state, viewModel) }

            item {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Files", style = MaterialTheme.typography.titleSmall)
            }

            items(state.items, key = { it.id.value }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ItemStatusGlyph(item.status)
                    Column(Modifier.weight(1f)) {
                        SingleLine(item.sourceRelativePath.toString())
                        item.statusReason?.let {
                            Text(
                                it.name.lowercase().replace('_', ' '),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // The reason is a category — "error permanent" says an
                        // item will not succeed on its own, and nothing about
                        // why. The adapter already wrote a sentence saying
                        // which call refused and what it said (§23, §26); until
                        // now it reached the database and stopped there, so a
                        // failure could only be diagnosed by someone holding
                        // the source. Two milestones in a row were debugged
                        // from screenshots that could have carried the answer.
                        item.lastErrorMessage?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** §13.1 counts each outcome separately, so the summary shows them separately. */
@Composable
private fun OutcomeCounts(state: DetailState) {
    val t = state.transfer ?: return
    val rows = buildList {
        if (t.completedFiles > 0) add("Completed" to t.completedFiles)
        if (t.duplicateFiles > 0) add("Already there" to t.duplicateFiles)
        if (t.unsupportedFiles > 0) add("Skipped, unsupported" to t.unsupportedFiles)
        if (t.sourceChangedFiles > 0) add("Changed at source" to t.sourceChangedFiles)
        if (t.conflictFiles > 0) add("In conflict" to t.conflictFiles)
        if (t.failedFiles > 0) add("Failed" to t.failedFiles)
        if (t.cancelledFiles > 0) add("Cancelled" to t.cancelledFiles)
    }
    if (rows.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        rows.forEach { (label, count) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text("$count", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Controls(state: DetailState, viewModel: TransferDetailViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.canPause -> Button(onClick = { viewModel.pause() }) { Text("Pause Transfer") }
            state.canResume -> Button(onClick = { viewModel.resume() }) { Text("Resume") }
            state.canRetry -> Button(onClick = { viewModel.retryIncomplete() }) {
                Text("Retry incomplete files")
            }
        }
        if (state.canCancel) {
            OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel Transfer") }
        }
    }
}
