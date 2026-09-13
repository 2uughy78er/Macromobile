package com.macromobile.inputmirror.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.macromobile.inputmirror.ui.screens.LogScreen
import com.macromobile.inputmirror.ui.screens.MainScreen
import com.macromobile.inputmirror.ui.screens.MirrorTestScreen
import com.macromobile.inputmirror.ui.screens.PermissionScreen
import com.macromobile.inputmirror.ui.screens.SettingsScreen

object Routes {
    const val MAIN = "main"
    const val TEST = "test"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val PERMISSION = "permission"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val viewModel: MirrorViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                viewModel = viewModel,
                onOpenTest = { navController.navigate(Routes.TEST) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPermission = { navController.navigate(Routes.PERMISSION) },
            )
        }
        composable(Routes.TEST) {
            MirrorTestScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.LOG) {
            LogScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.PERMISSION) {
            PermissionScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
