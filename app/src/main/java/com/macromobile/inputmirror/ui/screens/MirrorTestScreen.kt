package com.macromobile.inputmirror.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.ui.MirrorViewModel
import com.macromobile.inputmirror.ui.test.MirrorTestView

/**
 * 4분할 테스트 화면.
 *
 * MASTER 영역을 만지면 그 입력이 접근성 서비스를 통해 TARGET 영역으로 실제로 주입된다.
 * TARGET 영역에 선이 그려지면 주입이 통한 것이고, 안 그려지면 막힌 것이다.
 */
@Composable
fun MirrorTestScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    var testView by remember { mutableStateOf<MirrorTestView?>(null) }

    BackHandler {
        viewModel.stopMirroring()
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopMirroring() }
    }

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
                        view.onAreasChanged = { master, targets ->
                            viewModel.onAreasChanged(master, targets)
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

        Column(Modifier.padding(12.dp)) {
            val last = records.lastOrNull()
            Text(
                text = last?.let { viewModel.formatRecord(it) }
                    ?: "아직 기록이 없습니다. 미러링을 켜고 MASTER 영역을 만져보세요.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (last?.success == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "기록 ${records.size}개 · 실패 ${records.count { !it.success }}개",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text(if (mirroring) "■ 미러링 중지" else "▶ 미러링 시작")
                }
                OutlinedButton(
                    onClick = {
                        testView?.clearTraces()
                        viewModel.clearLogs()
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
        }
    }
}
