package com.macromobile.imagemacro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.storage.AppSettings
import com.macromobile.imagemacro.ui.MacroViewModel

/** 앱 전역 설정. 성능·디버깅·저장 공간을 다룬다. */
@Composable
fun SettingsScreen(viewModel: MacroViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var storageBytes by remember { mutableLongStateOf(-1L) }

    Scaffold(topBar = { MacroTopBar("설정", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "성능",
                subtitle = "화면을 자주 검사할수록 반응이 빠르지만 배터리를 더 씁니다.",
            ) {
                DropdownField(
                    label = "화면 검사 간격",
                    options = AppSettings.POLL_CHOICES,
                    selected = AppSettings.POLL_CHOICES.minByOrNull {
                        kotlin.math.abs(it - settings.pollIntervalMs)
                    } ?: 300L,
                    optionLabel = { "${it}ms" },
                    onSelect = { viewModel.updateSettings { s -> s.copy(pollIntervalMs = it) } },
                )
                if (settings.pollIntervalMs <= AppSettings.FAST_POLL_WARNING_MS) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "간격이 매우 짧습니다. CPU 사용량과 배터리 소모가 크게 늘어날 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                DropdownField(
                    label = "매칭 해상도",
                    options = AppSettings.ANALYSIS_SCALE_CHOICES,
                    selected = AppSettings.ANALYSIS_SCALE_CHOICES.minByOrNull {
                        kotlin.math.abs(it - settings.analysisScale)
                    } ?: 1f,
                    optionLabel = {
                        when (it) {
                            1.0f -> "원본 (가장 정확)"
                            0.75f -> "75% (조금 빠름)"
                            else -> "50% (가장 빠름)"
                        }
                    },
                    onSelect = { viewModel.updateSettings { s -> s.copy(analysisScale = it) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "화면을 줄여서 검사하면 훨씬 빠르지만 작은 이미지를 놓칠 수 있습니다. " +
                        "잘 못 찾으면 원본으로 되돌리세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "실행 동작") {
                SwitchRow(
                    label = "실행 중 오버레이 컨트롤러 표시",
                    checked = settings.showOverlayController,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(showOverlayController = it) }
                    },
                    helper = "다른 앱을 보는 중에도 매크로를 멈출 수 있습니다. 오버레이 권한이 필요합니다.",
                )
                SwitchRow(
                    label = "일시정지 중에도 타겟 감시",
                    checked = settings.monitorWhilePaused,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(monitorWhilePaused = it) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = "터치 위치 흔들림",
                    value = settings.tapJitterPx.toFloat(),
                    onValueChange = {
                        viewModel.updateSettings { s -> s.copy(tapJitterPx = it.toInt()) }
                    },
                    valueRange = 0f..20f,
                    steps = 19,
                    valueText = "${settings.tapJitterPx}px",
                    helper = "정확히 같은 좌표만 누르지 않도록 약간 흔듭니다. 0 이면 정확한 좌표를 누릅니다.",
                )
                LabeledSlider(
                    label = "대기 시간 흔들림",
                    value = settings.delayJitter,
                    onValueChange = {
                        viewModel.updateSettings { s -> s.copy(delayJitter = it) }
                    },
                    valueRange = 0f..0.5f,
                    valueText = "±${(settings.delayJitter * 100).toInt()}%",
                )
            }

            SectionCard(
                title = "디버깅",
                subtitle = "이미지를 잘 못 찾을 때 원인을 확인하는 데 씁니다.",
            ) {
                SwitchRow(
                    label = "실패한 화면 저장",
                    checked = settings.saveDebugScreenshots,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(saveDebugScreenshots = it) }
                    },
                    helper = "이미지를 못 찾거나 OCR 이 실패했을 때 그 순간의 화면을 저장합니다.",
                )
                SwitchRow(
                    label = "매칭 결과 표시",
                    checked = settings.showMatchDebugOverlay,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(showMatchDebugOverlay = it) }
                    },
                    helper = "타겟 발견 화면에서 찾은 위치를 사각형으로 보여줍니다.",
                )
                Spacer(Modifier.height(8.dp))
                NumberField(
                    label = "보관할 화면/로그 개수",
                    value = settings.logKeepCount.toLong(),
                    onValueChange = {
                        viewModel.updateSettings { s -> s.copy(logKeepCount = it.toInt()) }
                    },
                    suffix = "개",
                )
            }

            SectionCard(title = "저장 공간") {
                Text(
                    if (storageBytes < 0) {
                        "사용량을 확인하려면 아래 버튼을 누르세요."
                    } else {
                        "매크로·이미지·화면 저장에 ${formatBytes(storageBytes)} 를 쓰고 있습니다."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { storageBytes = viewModel.storageUsageBytes() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("사용량 확인")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.clearLogs()
                        storageBytes = -1L
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("저장된 화면·로그 지우기")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "매크로와 등록한 이미지는 지워지지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "이 앱에 대하여") {
                Text(
                    "루팅 없이 동작하는 범용 이미지 매크로입니다. " +
                        "화면 캡처와 이미지 분석은 모두 기기 안에서만 이뤄지며, " +
                        "어떤 이미지도 외부 서버로 전송되지 않습니다.\n\n" +
                        "앱에는 어떤 앱·게임의 이미지나 매크로 순서도 기본 포함되어 있지 않습니다. " +
                        "모든 이미지와 단계는 사용자가 직접 등록합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
