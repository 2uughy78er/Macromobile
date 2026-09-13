package com.macromobile.inputmirror.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.diag.DiagLog
import com.macromobile.inputmirror.diag.DiagStage
import com.macromobile.inputmirror.diag.TestStep
import com.macromobile.inputmirror.input.TestCase
import com.macromobile.inputmirror.input.findActivity
import com.macromobile.inputmirror.ui.MirrorViewModel
import com.macromobile.inputmirror.ui.test.MirrorTestView

private const val COMPONENT = "MirrorTestScreen"

/**
 * 4분할 테스트 화면 — 단계별로 켜는 판.
 *
 * MASTER 영역을 만지면 그 입력이 접근성 서비스를 통해 TARGET 영역에 실제로 주입된다.
 * 초록 선은 **실제로 전달된 터치**이고, 주황 십자는 **주입하려던 위치**다.
 * 둘이 겹치지 않으면 좌표 문제, 초록 선이 아예 없으면 전달 문제다.
 *
 * ## 왜 단계로 쪼갰나
 *
 * 예전 판은 이 화면 하나에서 좌표 변환·제스처 전송기·오버레이 뷰·로그를 한꺼번에
 * 초기화했다. 그래서 그중 하나가 예외를 던지면 화면이 통째로 죽었고, 로그가 없으니
 * 무엇이 죽였는지 알 길이 없었다. 이제 STEP 1~10 으로 하나씩 켜고, 각 단계의 시작과
 * 끝이 [DiagLog] 에 남는다. 크래시가 나면 마지막으로 남은 단계가 곧 범인의 위치다.
 */
@Composable
fun MirrorTestScreen(
    viewModel: MirrorViewModel,
    onBack: () -> Unit,
    onOpenDiag: () -> Unit = {},
) {
    // 컴포지션에 실제로 진입했다는 사실 자체를 남긴다. 여기까지 못 오면 화면 전환이 문제다.
    remember {
        DiagLog.start(DiagStage.TEST_SCREEN_COMPOSE_START, COMPONENT)
        Unit
    }

    val hostView = LocalView.current
    remember(hostView) {
        val activity = hostView.context.findActivity()
        DiagLog.start(
            DiagStage.TEST_SCREEN_ACTIVITY_CREATE,
            COMPONENT,
            detail = activity?.let { "${it::class.java.simpleName} 확인됨" }
                ?: "Activity 를 찾지 못했습니다",
        )
        if (activity != null) DiagLog.ok(DiagStage.TEST_SCREEN_ACTIVITY_CREATE, COMPONENT)
        Unit
    }

    var step by remember { mutableStateOf(TestStep.FULL) }

    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val planned by viewModel.plannedPoints.collectAsStateWithLifecycle()
    val results by viewModel.targetResults.collectAsStateWithLifecycle()
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val progress by viewModel.autoProgress.collectAsStateWithLifecycle()
    val diagRecords by DiagLog.records.collectAsStateWithLifecycle()
    var testView by remember { mutableStateOf<MirrorTestView?>(null) }
    var showPanel by remember { mutableStateOf(true) }

    BackHandler {
        viewModel.stopMirroring()
        onBack()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopMirroring() } }

    // STEP 2: 접근성 서비스 상태 확인.
    LaunchedEffect(step) {
        if (step.includes(TestStep.ACCESSIBILITY)) {
            DiagLog.runStage(DiagStage.ACCESSIBILITY_SERVICE_CHECK, COMPONENT) {
                viewModel.refreshCapability()
            }
        } else {
            DiagLog.skip(DiagStage.ACCESSIBILITY_SERVICE_CHECK, COMPONENT, "STEP ${step.number}")
        }
    }

    // STEP 3~4: 화면/창 정보 읽기. 테스트 뷰가 없어도 되도록 화면의 루트 뷰로 읽는다.
    // 루트 뷰의 컨텍스트는 Activity 이므로 디스플레이에 연결되어 있다.
    LaunchedEffect(step, hostView) {
        if (step.includes(TestStep.DISPLAY_METRICS)) {
            viewModel.captureEnvironment(
                view = hostView,
                readWindowMetrics = step.includes(TestStep.WINDOW_METRICS),
            )
        } else {
            DiagLog.skip(DiagStage.DISPLAY_METRICS_READ, COMPONENT, "STEP ${step.number}")
        }
    }

    // STEP 9~10: 전송기가 쓸 TARGET 개수.
    LaunchedEffect(step) {
        DiagLog.runStage(DiagStage.GESTURE_CONTROLLER_INITIALIZE, COMPONENT) {
            viewModel.setTargetLimit(step.targetCount)
        }
    }

    // 단계가 바뀌면 켜져 있던 미러링을 멈춘다. 낮은 단계에서 주입이 계속되면 안 된다.
    LaunchedEffect(step) {
        if (!step.includes(TestStep.ONE_TARGET) && mirroring) viewModel.stopMirroring()
    }

    // 화면이 한 프레임 실제로 그려진 뒤에 READY 를 남긴다. 컴포지션만으로는 증거가 안 된다.
    LaunchedEffect(step) {
        withFrameNanos { }
        DiagLog.ok(
            DiagStage.TEST_SCREEN_READY,
            COMPONENT,
            detail = "STEP ${step.number} · ${step.title}",
        )
    }

    LaunchedEffect(planned) { testView?.showPlannedPoints(planned) }
    LaunchedEffect(results) { testView?.showResults(results) }
    LaunchedEffect(step, testView) {
        testView?.let { view ->
            view.drawAreas = step.includes(TestStep.AREAS)
            view.reportGeometry = step.includes(TestStep.GEOMETRY)
            view.collectTouch = step.includes(TestStep.TOUCH)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (step.includes(TestStep.VIEW)) {
                AndroidView(
                    factory = { context ->
                        DiagLog.runStage(DiagStage.OVERLAY_INITIALIZE, COMPONENT) {
                            MirrorTestView(context).also { view ->
                                view.drawAreas = step.includes(TestStep.AREAS)
                                view.reportGeometry = step.includes(TestStep.GEOMETRY)
                                view.collectTouch = step.includes(TestStep.TOUCH)
                                view.onAreasChanged = { master, targets, geometry ->
                                    viewModel.onAreasChanged(master, targets, geometry)
                                    // 이제 View 를 넘긴다. 예전에는 Application 컨텍스트를
                                    // 넘겨 Context.getDisplay() 에서 바로 죽었다.
                                    viewModel.captureEnvironment(view)
                                }
                                view.onMasterDown = { viewModel.onMasterDown(it) }
                                view.onMasterMove = { viewModel.onMasterMove(it) }
                                view.onMasterUp = { tail ->
                                    viewModel.onMasterUp(tail, view.currentMasterPath())
                                }
                                view.onMasterCancel = { viewModel.onMasterCancel() }
                                testView = view
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DisposableEffect(Unit) {
                    DiagLog.skip(DiagStage.OVERLAY_INITIALIZE, COMPONENT, "STEP ${step.number}")
                    onDispose { }
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("STEP ${step.number} · ${step.title}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        step.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "이 단계에서는 테스트 뷰를 만들지 않습니다. " +
                            "이 화면이 뜨는데 STEP 5 에서 죽는다면 뷰 생성이 원인입니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Column(
            Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            StepSelector(current = step, onSelect = { step = it })

            Spacer(Modifier.height(8.dp))
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
                    enabled = step.includes(TestStep.ONE_TARGET),
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
                Text("단계 기록", style = MaterialTheme.typography.titleSmall)
                Text(
                    diagRecords.takeLast(12).joinToString("\n") { it.line() }
                        .ifBlank { "아직 기록이 없습니다." },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (diagRecords.any { it.isFailure }) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                TextButton(onClick = onOpenDiag) { Text("진단 · 크래시 기록 전체 보기") }

                Spacer(Modifier.height(8.dp))
                Text("좌표계", style = MaterialTheme.typography.titleSmall)
                Text(
                    environment?.report() ?: "화면 정보를 아직 읽지 않았습니다 (STEP 3 이상).",
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

/** STEP 1~10 을 고르는 줄. 크래시가 나면 한 단계씩 내려가며 범인을 찾는다. */
@Composable
private fun StepSelector(current: TestStep, onSelect: (TestStep) -> Unit) {
    Column {
        Text(
            "단계: ${current.label}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            current.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TestStep.entries.forEach { entry ->
                FilterChip(
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                    label = { Text("${entry.number}") },
                )
            }
        }
    }
}
