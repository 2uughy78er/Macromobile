package com.macromobile.inputmirror.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.input.TestCase
import com.macromobile.inputmirror.ui.MirrorViewModel
import com.macromobile.inputmirror.ui.test.MirrorTestView

/**
 * 4분할 테스트 화면.
 *
 * MASTER 영역을 만지면 그 입력이 접근성 서비스를 통해 TARGET 영역에 실제로 주입된다.
 * 초록 선은 **실제로 전달된 터치**이고, 주황 십자는 **주입하려던 위치**다.
 * 둘이 겹치지 않으면 좌표 문제, 초록 선이 아예 없으면 전달 문제다.
 */
@Composable
fun MirrorTestScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val planned by viewModel.plannedPoints.collectAsStateWithLifecycle()
    val results by viewModel.targetResults.collectAsStateWithLifecycle()
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val progress by viewModel.autoProgress.collectAsStateWithLifecycle()
    var testView by remember { mutableStateOf<MirrorTestView?>(null) }
    var showPanel by remember { mutableStateOf(true) }

    BackHandler {
        viewModel.stopMirroring()
        onBack()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopMirroring() } }

    LaunchedEffect(planned) { testView?.showPlannedPoints(planned) }
    LaunchedEffect(results) { testView?.showResults(results) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    MirrorTestView(context).also { view ->
                        testView = view
                        view.onAreasChanged = { master, targets, geometry ->
                            viewModel.onAreasChanged(master, targets, geometry)
                            viewModel.captureEnvironment(view)
                        }
                        view.onMasterDown = { viewModel.onMasterDown(it) }
                        view.onMasterMove = { viewModel.onMasterMove(it) }
                        view.onMasterUp = { tail ->
                            viewModel.onMasterUp(tail, view.currentMasterPath())
                        }
                        view.onMasterCancel = { viewModel.onMasterCancel() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            val last = records.lastOrNull()
            Text(
                text = last?.let { viewModel.formatRecord(it) }
                    ?: "미러링을 켜고 MASTER 영역을 만져보세요.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (last?.success == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "기록 ${records.size} · 실패 ${records.count { !it.success }}" +
                    (progress?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (mirroring) {
                            viewModel.stopMirroring()
                        } else {
                            testView?.clearTraces()
                            viewModel.startMirroring()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (mirroring) "■ 중지" else "▶ 시작")
                }
                OutlinedButton(
                    onClick = {
                        testView?.clearTraces()
                        viewModel.clearLogs()
                        viewModel.clearStats()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("지우기")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.stopMirroring()
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("나가기")
                }
            }

            TextButton(onClick = { showPanel = !showPanel }) {
                Text(if (showPanel) "검증 패널 접기 ▲" else "검증 패널 펼치기 ▼")
            }

            if (showPanel) {
                Spacer(Modifier.height(4.dp))
                Text("좌표계", style = MaterialTheme.typography.titleSmall)
                Text(
                    environment?.report() ?: "화면 정보를 읽는 중입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(12.dp))
                Text("자동 반복 테스트", style = MaterialTheme.typography.titleSmall)
                Text(
                    "같은 동작을 여러 번 흘려보내 성공률을 잽니다. " +
                        "'될 때도 있고 안 될 때도 있음'을 눈대중이 아니라 숫자로 확인합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.runAutoTest(TestCase.STANDARD.first(), 100) },
                        enabled = mirroring && progress == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("중앙 탭 ×100")
                    }
                    OutlinedButton(
                        onClick = { viewModel.runStandardSuite(20) },
                        enabled = mirroring && progress == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("TEST 1~9 ×20")
                    }
                }

                if (stats.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    stats.forEach { stat ->
                        Text(
                            stat.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("제스처 추적 (최근)", style = MaterialTheme.typography.titleSmall)
                Text(
                    viewModel.traceDump().lines().takeLast(14).joinToString("\n")
                        .ifBlank { "아직 기록이 없습니다." },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
