package com.dqmp.app.display.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dqmp.app.display.ui.screen.OutletDisplayScreen
import com.dqmp.app.display.ui.screen.SettingsScreen
import com.dqmp.app.display.ui.screen.QRConfigurationScreen
import com.dqmp.app.display.viewmodel.ConfigurationViewModel

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    val configViewModel: ConfigurationViewModel = viewModel()
    val configState by configViewModel.uiState.collectAsState()
    
    // Determine start destination based on configuration status
    val startDestination = if (configState.isConfigured) {
        "display/${configState.outletId}"
    } else {
        "qr-configuration"
    }
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("qr-configuration") {
            QRConfigurationScreen(
                onConfigurationComplete = { outletId ->
                    navController.navigate("display/$outletId") {
                        popUpTo("qr-configuration") { inclusive = true }
                    }
                }
            )
        }
        
        composable("display/{outletId}") { backStackEntry ->
            val outletId = backStackEntry.arguments?.getString("outletId") ?: "1"
            OutletDisplayScreen(
                outletId = outletId,
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onResetConfiguration = {
                    configViewModel.resetConfiguration()
                    navController.navigate("qr-configuration") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}