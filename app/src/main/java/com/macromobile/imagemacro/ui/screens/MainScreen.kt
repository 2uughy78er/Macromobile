package com.macromobile.imagemacro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.automation.RunState
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.findMainActivity

/**
 * 앱을 켜면 처음 보이는 화면.
 *
 * 권한 상태 → 매크로 선택 → 시작/일시정지/중지 라는 흐름을 한 화면에 담는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MacroViewModel,
    onOpenMacros: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onEditMacro: (Macro) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val serviceError by viewModel.serviceError.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }
    LaunchedEffect(status.state) { viewModel.refreshPermissions() }

    LaunchedEffect(message, serviceError) {
        val text = message ?: serviceError
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(notice) {
        val text = notice
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearNotice()
        }
    }

    val selected = remember(macros, settings.lastMacroId) {
        macros.firstOrNull { it.id == settings.lastMacroId } ?: macros.firstOrNull()
    }

    Scaffold(
        topBar = {
            MacroTopBar("이미지 매크로") {
                IconButton(onClick = onOpenLog) {
                    Icon(Icons.Default.Article, contentDescription = "실행 기록")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "설정")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "권한 상태",
                subtitle = "두 권한이 모두 켜져야 매크로가 실행됩니다.",
                trailing = {
                    TextButton(onClick = onOpenPermissions) { Text("설정") }
                },
            ) {
                StatusRow(
                    label = "접근성 서비스",
                    ok = permissions.accessibilityEnabled,
                    detail = if (permissions.accessibilityEnabled) {
                        "화면을 터치할 수 있습니다."
                    } else {
                        "켜야 화면을 터치할 수 있습니다."
                    },
                )
                StatusRow(
                    label = "화면 캡처",
                    ok = permissions.captureReady,
                    detail = if (permissions.captureReady) {
                        "화면을 볼 수 있습니다."
                    } else {
                        "허용해야 화면을 분석할 수 있습니다."
                    },
                    action = {
                        if (!permissions.captureReady) {
                            TextButton(onClick = { activity?.requestScreenCapture() }) {
                                Text("허용")
                            }
                        }
                    },
                )
                if (!permissions.openCvReady) {
                    StatusRow(
                        label = "이미지 처리 엔진",
                        ok = false,
                        detail = "OpenCV 를 불러오지 못했습니다. 앱을 다시 설치해주세요.",
                    )
                }
            }

            SectionCard(
                title = "매크로",
                subtitle = if (macros.isEmpty()) {
                    "아직 만든 매크로가 없습니다. 새로 만들어 시작해보세요."
                } else {
                    "실행할 매크로를 고르세요."
                },
                trailing = { TextButton(onClick = onOpenMacros) { Text("목록") } },
            ) {
                if (macros.isEmpty()) {
                    Button(onClick = onOpenMacros, modifier = Modifier.fillMaxWidth()) {
                        Text("새 매크로 만들기")
                    }
                } else {
                    DropdownField(
                        label = "실행할 매크로",
                        options = macros,
                        selected = selected ?: macros.first(),
                        optionLabel = { it.displayName() },
                        onSelect = { macro ->
                            viewModel.updateSettings { it.copy(lastMacroId = macro.id) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    selected?.let { macro ->
                        Text(
                            "단계 ${macro.enabledSteps.size}개 · 이미지 ${macro.templates.size}개 · " +
                                "타겟 ${macro.targets.size}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onEditMacro(macro) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.height(0.dp))
                            Text("  매크로 편집")
                        }
                    }
                }
            }

            SectionCard(
                title = "실행",
                subtitle = status.state.koreanLabel +
                    if (status.state == RunState.RUNNING) " · ${status.stepName}" else "",
            ) {
                if (status.state.isActive || status.state == RunState.TARGET_FOUND) {
                    RunProgress(
                        stepLabel = status.progressLabel,
                        cycleLabel = status.cycleLabel,
                        elapsedMs = status.elapsedMs,
                        totalSteps = status.totalSteps,
                        stepIndex = status.stepIndex,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val running = status.state == RunState.RUNNING
                    val paused = status.state == RunState.PAUSED

                    Button(
                        onClick = {
                            val macro = selected
                            if (macro == null) {
                                viewModel.showMessage("먼저 매크로를 만들어주세요.")
                            } else if (paused) {
                                viewModel.resumeMacro()
                            } else {
                                viewModel.startMacro(macro)
                            }
                        },
                        enabled = !running && (permissions.canRun || paused),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(if (paused) " 재개" else " 시작")
                    }

                    FilledTonalButton(
                        onClick = { viewModel.pauseMacro() },
                        enabled = running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Text(" 일시정지")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopMacro() },
                        enabled = status.state.isActive,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(" 중지")
                    }
                }

                if (!permissions.canRun) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "권한이 모두 준비되면 시작할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                status.errorMessage?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(
                title = "동작 녹화",
                subtitle = if (recording.active) {
                    "녹화 중 · ${recording.count}개 기록됨"
                } else {
                    "내가 하는 터치와 드래그를 그대로 단계로 만듭니다."
                },
            ) {
                if (recording.active) {
                    Text(
                        "화면 가장자리에 빨간 테두리가 보이는 동안 하는 동작이 기록됩니다.\n" +
                            "끝나면 아래 '녹화 중지'를 누르거나, 화면 위 컨트롤러의 ● 를 누르세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.stopRecording() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text("  녹화 중지하고 저장")
                    }
                } else {
                    Text(
                        "이미지를 등록하지 않고도 매크로를 만들 수 있습니다. " +
                            "녹화를 시작하면 앱이 내려가고, 그 뒤로 하시는 터치·드래그가 " +
                            "선택한 매크로 뒤에 단계로 붙습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (selected == null) {
                                viewModel.showMessage("먼저 녹화할 매크로를 골라주세요.")
                            } else if (viewModel.startRecording()) {
                                // 녹화가 실제로 시작됐을 때만 앱을 내린다.
                                activity?.minimizeApp()
                            }
                        },
                        enabled = !status.state.isActive,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                        Text("  녹화 시작")
                    }
                    if (status.state.isActive) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "매크로가 실행 중일 때는 녹화할 수 없습니다. 먼저 중지해주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!permissions.overlayGranted) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "'다른 앱 위에 표시' 권한이 필요합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (status.log.isNotEmpty()) {
                SectionCard(
                    title = "최근 기록",
                    trailing = { TextButton(onClick = onOpenLog) { Text("전체") } },
                ) {
                    status.log.takeLast(6).forEach { entry ->
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = logLevelColor(entry.level),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunProgress(
    stepLabel: String,
    cycleLabel: String,
    elapsedMs: Long,
    totalSteps: Int,
    stepIndex: Int,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text("단계 $stepLabel", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("반복 $cycleLabel", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        val progress = if (totalSteps > 0 && stepIndex >= 0) {
            (stepIndex + 1).toFloat() / totalSteps
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "실행 시간 ${formatDuration(elapsedMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
