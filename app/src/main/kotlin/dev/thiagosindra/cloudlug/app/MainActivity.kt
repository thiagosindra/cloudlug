package dev.thiagosindra.cloudlug.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.thiagosindra.cloudlug.feature.home.HomeScreen
import dev.thiagosindra.cloudlug.feature.newtransfer.NewTransferScreen
import dev.thiagosindra.cloudlug.feature.transferdetails.TransferDetailScreen
import dev.thiagosindra.cloudlug.ui.CloudLugTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudLugTheme { CloudLugNavHost() }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val NEW_TRANSFER = "new-transfer"
    const val DETAIL = "transfer/{transferId}"
    fun detail(id: String) = "transfer/$id"
}

/** The §24 screens: home, the wizard, and one transfer's detail. */
@Composable
fun CloudLugNavHost() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewTransfer = { navController.navigate(Routes.NEW_TRANSFER) },
                onOpenTransfer = { navController.navigate(Routes.detail(it.value)) },
            )
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
