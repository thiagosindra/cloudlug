package dev.thiagosindra.cloudlug.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.thiagosindra.cloudlug.feature.accounts.AccountsScreen
import dev.thiagosindra.cloudlug.feature.home.HomeScreen
import dev.thiagosindra.cloudlug.feature.newtransfer.NewTransferScreen
import dev.thiagosindra.cloudlug.feature.transferdetails.TransferDetailScreen
import dev.thiagosindra.cloudlug.app.crash.CrashReportScreen
import dev.thiagosindra.cloudlug.app.crash.crashReporter
import dev.thiagosindra.cloudlug.ui.CloudLugTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read once, before composing: consuming clears the file, so a crash
        // is shown on the next launch and not the one after that.
        val pendingCrash = crashReporter(this).consumePendingReport()

        setContent {
            CloudLugTheme {
                var crash by remember { mutableStateOf(pendingCrash) }
                val report = crash
                if (report != null) {
                    CrashReportScreen(
                        report = report,
                        onShare = { share(report) },
                        onDismiss = { crash = null },
                    )
                } else {
                    CloudLugNavHost()
                }
            }
        }
    }

    /**
     * Hands the text to whatever the user picks. Plain text rather than a
     * FileProvider URI: the report never leaves `filesDir` this way, and the
     * user sees exactly what they are sending before they send it.
     */
    private fun share(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CloudLug crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share crash report"))
    }
}

private object Routes {
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val NEW_TRANSFER = "new-transfer"
    const val DETAIL = "transfer/{transferId}"
    fun detail(id: String) = "transfer/$id"
}

/** The §24 screens: home, accounts, the wizard, and one transfer's detail. */
@Composable
fun CloudLugNavHost() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewTransfer = { navController.navigate(Routes.NEW_TRANSFER) },
                onOpenTransfer = { navController.navigate(Routes.detail(it.value)) },
                onAccounts = { navController.navigate(Routes.ACCOUNTS) },
            )
        }

        // §24.5. Reached from home rather than given a bottom-bar tab of its
        // own: it is a place people visit twice, not a place they live
        // (ADR-0028).
        composable(Routes.ACCOUNTS) {
            AccountsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.NEW_TRANSFER) {
            NewTransferScreen(
                onBack = { navController.popBackStack() },
                onStarted = { id ->
                    // Replace the wizard rather than stacking on it: backing out
                    // of a started transfer should reach home, not step 5.
                    navController.navigate(Routes.detail(id.value)) {
                        popUpTo(Routes.NEW_TRANSFER) { inclusive = true }
                    }
                },
            )
        }

        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("transferId") { type = NavType.StringType }),
        ) {
            TransferDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
