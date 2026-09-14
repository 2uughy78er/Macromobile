package com.macromobile.inputmirror.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.macromobile.inputmirror.input.findActivity
import com.macromobile.inputmirror.mirror.MirrorState
import com.macromobile.inputmirror.model.LayoutStatus
import com.macromobile.inputmirror.model.MirrorLayout
import com.macromobile.inputmirror.model.MirrorRegion
import com.macromobile.inputmirror.ui.SetupViewModel

/**
 * 본 모드 홈 화면.
 *
 * MASTER 하나와 TARGET 여럿을 지정하고 START/PAUSE/STOP 으로 미러링을 돌린다.
 * 영역을 지정할 때는 이 화면이 스스로 뒤로 물러나 진짜 게임 화면이 보이게 한다.
 */
@Composable
fun HomeScreen(
    viewModel: SetupViewModel,
    onOpenTestMode: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenDiag: () -> Unit,
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val failures by viewModel.failures.collectAsStateWithLifecycle()
    val gestures by viewModel.gestures.collectAsStateWithLifecycle()
    val results by viewModel.targetResults.collectAsStateWithLifecycle()
    val blocked by viewModel.blockedReason.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val windowDump by viewModel.windowDump.collectAsStateWithLifecycle()
    val probeResults by viewModel.probeResults.collectAsStateWithLifecycle()
    val probeProgress by viewModel.probeProgress.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity: Activity? = remember(context) { context.findActivity() }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    /** 영역 지정 오버레이를 띄우고, 진짜 화면이 보이도록 이 화면을 뒤로 보낸다. */
    fun edit(open: () -> Boolean) {
        if (open()) activity?.moveTaskToBack(true)
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            MirrorTopBar("Input Mirror") {
                IconButton(onClick = onOpenLog) {
                    Icon(Icons.Default.Article, contentDescription = "로그")
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
            StatusCard(layout, state, blocked, viewModel, onOpenPermission)

            SectionCard(
                title = "MASTER",
                subtitle = "직접 조작할 게임의 영역입니다.",
                trailing = {
                    TextButton(onClick = { edit { viewModel.beginEditMaster() } }) {
                        Text(if (layout.master == null) "영역 설정" else "다시 설정")
                    }
                },
            ) {
                val master = layout.master
                if (master == null) {
                    Text(
                        "아직 지정되지 않았습니다. 게임들을 분할 화면으로 띄운 뒤 " +
                            "'영역 설정' 을 누르세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    RegionRow(master, results[master.name])
                }
            }

            SectionCard(
                title = "TARGET ${layout.targets.size}개",
                subtitle = "MASTER 의 입력을 그대로 받을 영역들입니다.",
                trailing = {
                    TextButton(onClick = { edit { viewModel.beginEditTarget(null) } }) {
                        Text("+ 추가")
                    }
                },
            ) {
                if (layout.targets.isEmpty()) {
                    Text(
                        "아직 없습니다. '+ 추가' 로 영역을 그려주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                layout.targets.forEach { target ->
                    Spacer(Modifier.height(12.dp))
                    RegionRow(target, results[target.name])
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { edit { viewModel.beginEditTarget(target) } }) {
                            Text("영역")
                        }
                        TextButton(
                            onClick = {
                                viewModel.setTargetEnabled(target.id, !target.enabled)
                            },
                        ) {
                            Text(if (target.enabled) "사용 중" else "꺼짐")
                        }
                        TextButton(
                            onClick = {
                                viewModel.setTargetDeliverInput(target.id, !target.deliverInput)
                            },
                        ) {
                            Text(if (target.deliverInput) "입력 켜짐" else "입력 꺼짐")
                        }
                        TextButton(onClick = { viewModel.removeTarget(target.id) }) {
                            Text("삭제")
                        }
                    }
                }
            }

            ControlCard(state, viewModel)

            SectionCard(
                title = "동시 주입 측정",
                subtitle = "대상 여럿에 한꺼번에 주입이 실제로 되는지 숫자로 확인합니다.",
            ) {
                Text(
                    "한 제스처에 손가락을 여러 개 담았을 때 그것들이 서로 다른 창에 각각 " +
                        "전달되는지는 안드로이드 문서가 보장하지 않습니다. 그래서 재봅니다. " +
                        "MASTER 한가운데를 누르는 동작을 방식별로 흘려보내고 대상마다 결과를 셉니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.runInjectionProbe() },
                    enabled = state == MirrorState.RUNNING && probeProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(probeProgress ?: "측정 시작 (방식 3종 × 10회)")
                }
                if (state != MirrorState.RUNNING) {
                    Text(
                        "START 를 누른 뒤에 잴 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (probeResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    probeResults.forEach { result ->
                        Text(
                            result.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (result.allCompleted) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val best = probeResults.firstOrNull { it.allCompleted }
                    Text(
                        best?.let { "→ ${it.mode.koreanLabel} 이 모든 대상에서 통했습니다." }
                            ?: "→ 모든 대상에 매번 통한 방식이 없습니다. " +
                            "위 숫자가 이 기기의 실제 한계입니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (failures.isNotEmpty()) {
                SectionCard(
                    title = "실패 기록 ${failures.size}건",
                    subtitle = "원인별로 구분해 남깁니다.",
                    trailing = {
                        TextButton(onClick = { viewModel.clearRecords() }) { Text("지우기") }
                    },
                ) {
                    failures.takeLast(8).reversed().forEach { failure ->
                        Text(
                            failure.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (gestures.isNotEmpty()) {
                SectionCard(
                    title = "최근 제스처",
                    subtitle = "MASTER 좌표와 대상별 변환 좌표, 그리고 결과입니다.",
                ) {
                    Text(
                        gestures.takeLast(3).reversed()
                            .joinToString("\n\n") { it.describe() },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            SectionCard(
                title = "화면에 떠 있는 창",
                subtitle = "시스템이 알려준 실제 창 경계입니다. 영역 지정의 참고 자료입니다.",
                trailing = {
                    TextButton(onClick = { viewModel.refreshWindowDump() }) { Text("읽기") }
                },
            ) {
                Text(
                    windowDump ?: "'읽기' 를 누르면 지금 화면의 창 목록을 보여줍니다.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            SectionCard(
                title = "좌표 확인",
                subtitle = "저장된 값 그대로입니다.",
            ) {
                Text(
                    layout.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "지금 화면: ${viewModel.screenNow().describe()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (state == MirrorState.RUNNING || state == MirrorState.PAUSED) {
                SectionCard(
                    title = "메모리 아끼기",
                    subtitle = "게임이 자꾸 꺼진다면 이 화면부터 내리세요.",
                ) {
                    Text(
                        "미러링은 접근성 서비스가 돌립니다. 이 화면을 완전히 닫아도 계속 " +
                            "동작하고, 떠 있는 버튼으로 정지할 수 있습니다. 화면을 닫으면 " +
                            "Compose UI 가 쓰던 메모리가 반환되어 게임이 쓸 몫이 늘어납니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { activity?.finish() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("앱 화면 완전히 닫기 (미러링은 계속)")
                    }
                }
            }

            OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
                Text("테스트 모드 (빈 영역 4개로 검증)")
            }
            OutlinedButton(onClick = onOpenDiag, modifier = Modifier.fillMaxWidth()) {
                Text("진단 · 크래시 기록")
            }
            TextButton(
                onClick = { viewModel.clearLayout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("영역 설정 모두 지우기")
            }
        }
    }
}

@Composable
private fun StatusCard(
    layout: MirrorLayout,
    state: MirrorState,
    blocked: String?,
    viewModel: SetupViewModel,
    onOpenPermission: () -> Unit,
) {
    val connected = viewModel.isAccessibilityConnected
    SectionCard(
        title = "상태: ${state.koreanLabel}",
        subtitle = if (connected) "접근성 서비스 연결됨" else "접근성 서비스가 꺼져 있습니다.",
        trailing = {
            if (!connected) {
                TextButton(onClick = onOpenPermission) { Text("권한") }
            }
        },
    ) {
        // 배치가 바뀌면 이 카드도 다시 그려지도록 배치를 인자로 받아 여기서 판단한다.
        val status = layout.status(viewModel.screenNow())
        if (!status.isReady) {
            Text(
                status.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is LayoutStatus.ScreenChanged) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Text("시작할 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
        }
        blocked?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ControlCard(state: MirrorState, viewModel: SetupViewModel) {
    SectionCard(
        title = "조작",
        subtitle = "시작하면 MASTER 영역 위에 입력판이 덮입니다. 게임이 아니라 그 위를 만지세요.",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.start() },
                enabled = state != MirrorState.RUNNING && state != MirrorState.PAUSED,
                modifier = Modifier.weight(1f),
            ) { Text("START") }

            OutlinedButton(
                onClick = {
                    if (state == MirrorState.PAUSED) viewModel.resume() else viewModel.pause()
                },
                enabled = state == MirrorState.RUNNING || state == MirrorState.PAUSED,
                modifier = Modifier.weight(1f),
            ) { Text(if (state == MirrorState.PAUSED) "RESUME" else "PAUSE") }

            OutlinedButton(
                onClick = { viewModel.stop() },
                enabled = state != MirrorState.IDLE && state != MirrorState.STOPPED,
                modifier = Modifier.weight(1f),
            ) { Text("STOP") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "누르기와 끌기는 손을 뗀 뒤에 대상으로 전달됩니다. " +
                "안드로이드가 주입할 때 진행 중인 터치를 취소하기 때문에, 손가락이 화면에 " +
                "있는 동안 실시간으로 보낼 방법이 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RegionRow(region: MirrorRegion, result: String?) {
    val b = region.bounds
    Text(
        region.name + (region.packageName?.let { "  ($it)" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "L${b.left} T${b.top} R${b.right} B${b.bottom}   ${b.width} × ${b.height}" +
            if (region.delayMs > 0) "   +${region.delayMs}ms" else "",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    result?.let {
        Text(
            "마지막 결과: $it",
            style = MaterialTheme.typography.bodySmall,
            color = if (it == "COMPLETED") {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
