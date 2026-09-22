package dev.thiagosindra.cloudlug.feature.accounts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.AccountRoles
import dev.thiagosindra.cloudlug.provider.CloudAccount
import dev.thiagosindra.cloudlug.ui.providerLabel

/**
 * §24.5: the accounts screen, in the minimal form ADR-0028 settled on.
 *
 * One row per provider this build supports, connected or not. §7's granted
 * scopes are shown rather than assumed, because they are what decides whether
 * an account can be a source, a destination or neither — and a user who
 * declined a scope at the consent screen otherwise finds out halfway through
 * the wizard, with nothing on screen explaining why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // §8.1's Custom Tab. The result comes back as an Intent the connector
    // reads; a null one is the user pressing back in the browser.
    var connecting by remember { mutableStateOf<ProviderType?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        connecting?.let { viewModel.onAuthorizationResult(it, result.data) }
        connecting = null
    }

    // The intent is built in the ViewModel, not in the button's onClick.
    // Building it can fail — AppAuth needs a browser and there may not be one —
    // and an exception thrown from a composable's onClick is uncaught.
    state.launch?.let { pending ->
        LaunchedEffect(pending.id) {
            connecting = pending.provider
            launcher.launch(pending.intent)
            viewModel.onAuthorizationLaunched()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                // A word rather than a glyph, matching the "Accounts" action
                // that leads here. This app ships no icon set, and "<" is a
                // back affordance only to someone who already knows.
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(
                        "Connect the cloud accounts you want to move files between. " +
                            "CloudLug signs in through your browser and never sees your password.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                items(state.rows, key = { it.provider.name }) { row ->
                    ProviderCard(
                        row = row,
                        enabled = !state.working,
                        onConnect = { viewModel.requestConnect(row.provider) },
                        onDisconnect = viewModel::requestDisconnect,
                    )
                }
            }
        }
    }

    state.pendingDisconnect?.let { pending ->
        DisconnectDialog(
            name = pending.account.displayEmail ?: pending.account.displayName ?: providerLabel(pending.account.provider),
            activeTransfers = pending.activeTransfers,
            onConfirm = viewModel::confirmDisconnect,
            onDismiss = viewModel::dismissDisconnect,
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ProviderCard(
    row: AccountRow,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: (CloudAccount) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(providerLabel(row.provider), style = MaterialTheme.typography.titleMedium)

            if (!row.connected) {
                Text(
                    if (row.connectable) "Not connected" else "Not supported in this version yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            row.accounts.forEachIndexed { index, connected ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ConnectedAccountBlock(
                    connected = connected,
                    enabled = enabled,
                    onDisconnect = { onDisconnect(connected.account) },
                )
            }

            if (row.connectable) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Two accounts of one provider are the two ends of a
                    // transfer (§2.2 as amended), so connecting another is a
                    // normal thing to want rather than an edge case.
                    OutlinedButton(onClick = onConnect, enabled = enabled) {
                        Text(if (row.connected) "Connect another account" else "Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedAccountBlock(
    connected: ConnectedAccount,
    enabled: Boolean,
    onDisconnect: () -> Unit,
) {
    val account = connected.account
    Column(Modifier.padding(top = 8.dp)) {
        account.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        account.displayEmail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        connected.roles?.let { RoleSummary(it) }
        GrantedScopes(account.grantedScopes)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDisconnect, enabled = enabled) { Text("Disconnect") }
        }
    }
}

/**
 * §7 in a sentence.
 *
 * The scopes below say what was granted; this says what it means, because
 * "files.content.write" is not an answer to "can I copy things into this?".
 */
@Composable
private fun RoleSummary(roles: AccountRoles) {
    val summary = when {
        roles.canBeSource && roles.canBeDestination -> "Can be a source or a destination"
        roles.canBeSource -> "Can be a source only"
        roles.canBeDestination -> "Can be a destination only"
        else -> "Can't transfer files — reconnect and accept all permissions"
    }
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = if (roles.canDoNothing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * §7's granted scopes, shown verbatim: this is the account's actual authority.
 *
 * Bordered labels rather than chips. These were `AssistChip(enabled = false)`,
 * which Material draws at 38% alpha — so they read as *unavailable* rather
 * than as information, and were hard to read besides. They are not controls
 * and should not look like disabled ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrantedScopes(scopes: Set<String>) {
    if (scopes.isEmpty()) return
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        scopes.sorted().forEach { scope ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    scope,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DisconnectDialog(
    name: String,
    activeTransfers: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect $name?") },
        text = {
            Column {
                Text("CloudLug will revoke its access and forget the sign-in. You can connect again at any time.")
                if (activeTransfers > 0) {
                    Text(
                        if (activeTransfers == 1) {
                            "One unfinished transfer uses this account and will stop."
                        } else {
                            "$activeTransfers unfinished transfers use this account and will stop."
                        },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
