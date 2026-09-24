package dev.thiagosindra.cloudlug.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.thiagosindra.cloudlug.BuildConfig
import dev.thiagosindra.cloudlug.app.di.DemoAccounts
import dev.thiagosindra.cloudlug.app.di.DemoPace
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import dev.thiagosindra.cloudlug.feature.accounts.AccountsScreen
import dev.thiagosindra.cloudlug.feature.home.HomeScreen
import dev.thiagosindra.cloudlug.feature.newtransfer.NewTransferScreen
import dev.thiagosindra.cloudlug.feature.transferdetails.TransferDetailScreen
import dev.thiagosindra.cloudlug.app.crash.CrashReportScreen
import dev.thiagosindra.cloudlug.app.crash.crashReporter
import dev.thiagosindra.cloudlug.scheduling.TransferScheduler
import dev.thiagosindra.cloudlug.ui.CloudLugTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var demoAccounts: DemoAccounts

    @Inject
    lateinit var scheduler: TransferScheduler

    @Inject
    lateinit var demoPace: DemoPace

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // §24.2 picks accounts now, so the demo provider needs a row in §12.4
        // to be pickable at all. Debug builds only, never a credential — see
        // DemoAccounts.
        //
        // Here rather than in Application.onCreate, which runs once per
        // process: the instrumented tests share one, so a test that
        // disconnects a demo account left the next one without it. Seeding per
        // launch makes them order-independent, and makes the demo rows behave
        // like what they are — a fixture the debug build always has, rather
        // than state a disconnect can permanently remove.
        if (BuildConfig.DEBUG) {
            lifecycleScope.launch { demoAccounts.seed() }

            // §31.4's harness kills CloudLug mid-file, and it lives in another
            // process: this extra is the only way it can ask for a transfer
            // slow enough to still be running when the kill lands. Absent on
            // every other launch, which leaves the demo providers instant —
            // see DemoPace.
            demoPace.set(intent.getLongExtra(EXTRA_DEMO_PACE_MS, 0L).milliseconds)
        }

        // §2.4: the database is authoritative and a worker is disposable, so
        // opening the app is a chance to put the schedule back together. It
        // also covers the one case a boot receiver cannot: a user-initiated job
        // may only be scheduled while the app is visible, so a transfer that
        // survived a reboot without its job is picked up here.
        lifecycleScope.launch { scheduler.reconcile() }

        // §24.4's notification is mandatory for a user-initiated job on 34+, so
        // asking is not optional either. Asked on first launch rather than at
        // the moment a transfer starts: being refused here costs a notification,
        // being refused there costs the job.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
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

/** Request code for §24.4's runtime notification permission (API 33+). */
private const val REQUEST_NOTIFICATIONS = 1

/**
 * Milliseconds the demo providers spend on each chunk, read off the launch
 * intent in debug builds only (§31.4). `:tools:recovery-test` names this string
 * too; it cannot depend on `:app`, which is the whole point of that module.
 */
private const val EXTRA_DEMO_PACE_MS = "dev.thiagosindra.cloudlug.DEMO_PACE_MS"
