package com.macromobile.inputmirror.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.macromobile.inputmirror.diag.DiagLog
import com.macromobile.inputmirror.diag.DiagStage
import com.macromobile.inputmirror.ui.screens.DiagScreen
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
    const val DIAG = "diag"
}

private const val NAV_COMPONENT = "AppNavigation"

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val viewModel: MirrorViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                viewModel = viewModel,
                onOpenTest = {
                    // 버튼을 누른 사실과 화면 전환을 시작한 사실을 따로 남긴다.
                    // 둘 사이에서 죽으면 전환 자체가 문제라는 뜻이다.
                    DiagLog.start(DiagStage.TEST_SCREEN_CLICK, NAV_COMPONENT)
                    DiagLog.ok(DiagStage.TEST_SCREEN_CLICK, NAV_COMPONENT)
                    DiagLog.runStage(DiagStage.TEST_SCREEN_NAVIGATION_START, NAV_COMPONENT) {
                        navController.navigate(Routes.TEST)
                    }
                },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPermission = { navController.navigate(Routes.PERMISSION) },
                onOpenDiag = { navController.navigate(Routes.DIAG) },
            )
        }
        composable(Routes.TEST) {
            MirrorTestScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenDiag = { navController.navigate(Routes.DIAG) },
            )
        }
        composable(Routes.LOG) {
            LogScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAG) {
            DiagScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PERMISSION) {
            PermissionScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
