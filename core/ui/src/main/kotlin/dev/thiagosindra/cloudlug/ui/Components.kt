package dev.thiagosindra.cloudlug.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.thiagosindra.cloudlug.model.TransferItemStatus

/** Which leg of the §24.3 hop diagram is active. */
enum class TransferStage { DOWNLOADING, UPLOADING, VERIFYING, IDLE }

/**
 * The `Dropbox ●==> Phone ●--> Google Drive ○` indicator of spec §24.3.
 *
 * During upload the highlighted arrow moves from source->phone to
 * phone->destination; during verification neither arrow is active and the
 * destination is highlighted.
 */
@Composable
fun ProviderHopIndicator(
    sourceLabel: String,
    destinationLabel: String,
    stage: TransferStage,
    modifier: Modifier = Modifier,
) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant

    val sourceOn = stage == TransferStage.DOWNLOADING
    val phoneOn = stage == TransferStage.DOWNLOADING || stage == TransferStage.UPLOADING
    val destinationOn = stage == TransferStage.VERIFYING

    val description = when (stage) {
        TransferStage.DOWNLOADING -> "Downloading from $sourceLabel to this device"
        TransferStage.UPLOADING -> "Uploading from this device to $destinationLabel"
        TransferStage.VERIFYING -> "Verifying at $destinationLabel"
        TransferStage.IDLE -> "Idle"
    }

    Column(modifier.semantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (sourceOn) active else idle)
            Leg(active = stage == TransferStage.DOWNLOADING, activeColor = active, idleColor = idle)
            Dot(if (phoneOn) active else idle)
            Leg(active = stage == TransferStage.UPLOADING, activeColor = active, idleColor = idle)
            Dot(if (destinationOn) active else idle)
        }
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("This device", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(destinationLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Text(
            when (stage) {
                TransferStage.DOWNLOADING -> "Downloading"
                TransferStage.UPLOADING -> "Uploading"
                TransferStage.VERIFYING -> "Verifying"
                TransferStage.IDLE -> "Idle"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Surface(Modifier.size(12.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun Leg(active: Boolean, activeColor: Color, idleColor: Color) {
    Surface(
        Modifier
            .padding(horizontal = 6.dp)
            .width(56.dp)
            .size(height = 3.dp, width = 56.dp),
        color = if (active) activeColor else idleColor,
    ) {}
}

/** Aggregate progress: bar, percentage, file counts and byte counts (spec §24.1, §24.3). */
@Composable
fun TransferProgress(
    fraction: Float,
    completedFiles: Int,
    totalFiles: Int,
    completedBytes: Long,
    totalBytes: Long,
    bytesAreLowerBound: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "${(fraction * 100).toInt()} percent complete"
                },
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "${formatCount(completedFiles)} / ${formatCount(totalFiles)} files",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (totalBytes > 0) {
            Text(
                buildString {
                    append(formatBytes(completedBytes))
                    append(" / ")
                    if (bytesAreLowerBound) append("at least ")
                    append(formatBytes(totalBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The per-file glyphs of the §24.3 tree: done, in flight, pending, or settled otherwise. */
@Composable
fun ItemStatusGlyph(status: TransferItemStatus) {
    val (glyph, color) = when (status) {
        TransferItemStatus.COMPLETED -> "✓" to MaterialTheme.colorScheme.primary
        TransferItemStatus.SKIPPED_DUPLICATE -> "=" to MaterialTheme.colorScheme.onSurfaceVariant
        TransferItemStatus.SKIPPED_UNSUPPORTED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
        TransferItemStatus.SOURCE_CHANGED -> "!" to MaterialTheme.colorScheme.tertiary
        TransferItemStatus.CONFLICT -> "!" to MaterialTheme.colorScheme.error
        TransferItemStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
        TransferItemStatus.CANCELLED -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
        TransferItemStatus.PENDING -> "○" to MaterialTheme.colorScheme.outline
        else -> "→" to MaterialTheme.colorScheme.primary
    }
    Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
}

/** A one-line label that truncates rather than wrapping, for file and path rows. */
@Composable
fun SingleLine(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(text, modifier = modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
