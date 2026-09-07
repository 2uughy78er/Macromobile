package com.macromobile.imagemacro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macromobile.imagemacro.automation.RunState
import com.macromobile.imagemacro.ui.screens.CaptureMode
import com.macromobile.imagemacro.ui.screens.ImageCaptureScreen
import com.macromobile.imagemacro.ui.screens.MacroEditorScreen
import com.macromobile.imagemacro.ui.screens.MacroListScreen
import com.macromobile.imagemacro.ui.screens.MainScreen
import com.macromobile.imagemacro.ui.screens.PermissionScreen
import com.macromobile.imagemacro.ui.screens.RunLogScreen
import com.macromobile.imagemacro.ui.screens.SettingsScreen
import com.macromobile.imagemacro.ui.screens.StepEditorScreen
import com.macromobile.imagemacro.ui.screens.TargetEditorScreen
import com.macromobile.imagemacro.ui.screens.TargetFoundScreen

object Routes {
    const val MAIN = "main"
    const val MACROS = "macros"
    const val PERMISSIONS = "permissions"
    const val SETTINGS = "settings"
    const val LOG = "log"
    const val TARGET_FOUND = "target_found"

    fun editor(macroId: String) = "editor/$macroId"
    fun step(macroId: String, stepId: String) = "step/$macroId/$stepId"
    fun capture(macroId: String, mode: CaptureMode) = "capture/$macroId/${mode.name}"
    fun targets(macroId: String) = "targets/$macroId"

    const val EDITOR_PATTERN = "editor/{macroId}"
    const val STEP_PATTERN = "step/{macroId}/{stepId}"
    const val CAPTURE_PATTERN = "capture/{macroId}/{mode}"
    const val TARGETS_PATTERN = "targets/{macroId}"

    /** 새 단계를 만들 때 쓰는 표시. */
    const val NEW_STEP = "new"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val viewModel: MacroViewModel = viewModel()
    val status by viewModel.status.collectAsStateWithLifecycle()

    // 타겟을 찾으면 어느 화면에 있든 결과 화면을 띄운다.
    LaunchedEffect(status.state) {
        if (status.state == RunState.TARGET_FOUND) {
            navController.navigate(Routes.TARGET_FOUND) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                viewModel = viewModel,
                onOpenMacros = { navController.navigate(Routes.MACROS) },
                onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onEditMacro = { navController.navigate(Routes.editor(it.id)) },
            )
        }

        composable(Routes.MACROS) {
            MacroListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenMacro = { navController.navigate(Routes.editor(it)) },
            )
        }

        composable(Routes.PERMISSIONS) {
            PermissionScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.LOG) {
            RunLogScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.TARGET_FOUND) {
            TargetFoundScreen(
                viewModel = viewModel,
                onClose = {
                    viewModel.dismissTargetFound()
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                },
                onEditMacro = { macroId ->
                    viewModel.dismissTargetFound()
                    navController.navigate(Routes.editor(macroId))
                },
            )
        }

        composable(
            Routes.EDITOR_PATTERN,
            arguments = listOf(navArgument("macroId") { type = NavType.StringType }),
        ) { entry ->
            val macroId = entry.arguments?.getString("macroId").orEmpty()
            MacroEditorScreen(
                viewModel = viewModel,
                macroId = macroId,
                onBack = { navController.popBackStack() },
                onAddStep = { navController.navigate(Routes.step(macroId, Routes.NEW_STEP)) },
                onEditStep = { navController.navigate(Routes.step(macroId, it.id)) },
                onRegisterTemplate = {
                    navController.navigate(Routes.capture(macroId, CaptureMode.TEMPLATE))
                },
                onOpenTargets = { navController.navigate(Routes.targets(macroId)) },
            )
        }

        composable(
            Routes.STEP_PATTERN,
            arguments = listOf(
                navArgument("macroId") { type = NavType.StringType },
                navArgument("stepId") { type = NavType.StringType },
            ),
        ) { entry ->
            StepEditorScreen(
                viewModel = viewModel,
                macroId = entry.arguments?.getString("macroId").orEmpty(),
                stepId = entry.arguments?.getString("stepId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.CAPTURE_PATTERN,
            arguments = listOf(
                navArgument("macroId") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            val mode = runCatching {
                CaptureMode.valueOf(entry.arguments?.getString("mode").orEmpty())
            }.getOrDefault(CaptureMode.TEMPLATE)
            ImageCaptureScreen(
                viewModel = viewModel,
                macroId = entry.arguments?.getString("macroId").orEmpty(),
                mode = mode,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.TARGETS_PATTERN,
            arguments = listOf(navArgument("macroId") { type = NavType.StringType }),
        ) { entry ->
            val macroId = entry.arguments?.getString("macroId").orEmpty()
            TargetEditorScreen(
                viewModel = viewModel,
                macroId = macroId,
                onBack = { navController.popBackStack() },
                onRegisterTarget = {
                    navController.navigate(Routes.capture(macroId, CaptureMode.TARGET))
                },
            )
        }
    }
}
