package com.macromobile.inputmirror.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.model.DispatchMode
import com.macromobile.inputmirror.model.FitMode
import com.macromobile.inputmirror.model.MirrorMode
import com.macromobile.inputmirror.model.MirrorSettings
import com.macromobile.inputmirror.ui.MirrorViewModel

@Composable
fun SettingsScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = { MirrorTopBar("설정", onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "전송 방식", subtitle = settings.mode.description) {
                DropdownField(
                    label = "언제 대상에 보낼지",
                    options = MirrorMode.entries,
                    selected = settings.mode,
                    optionLabel = { it.koreanLabel },
                    onSelect = { m -> viewModel.updateSettings { it.copy(mode = m) } },
                )
                Spacer(Modifier.height(12.dp))
                DropdownField(
                    label = "추가 지연",
                    options = MirrorSettings.DELAY_CHOICES,
                    selected = MirrorSettings.DELAY_CHOICES.minByOrNull {
                        kotlin.math.abs(it - settings.inputDelayMs)
                    } ?: 0L,
                    optionLabel = { if (it == 0L) "없음 (가장 빠름)" else "${it}ms" },
                    onSelect = { d -> viewModel.updateSettings { it.copy(inputDelayMs = d) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "대상 앱이 너무 빠른 입력을 놓칠 때만 지연을 주세요. 기본은 없음입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "대상이 여럿일 때 (MODE A/B/C)",
                subtitle = settings.dispatchMode.description,
            ) {
                DropdownField(
                    label = "전달 방식",
                    options = DispatchMode.entries,
                    selected = settings.dispatchMode,
                    optionLabel = { it.koreanLabel },
                    onSelect = { m -> viewModel.updateSettings { it.copy(dispatchMode = m) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "어느 방식이 실제로 통하는지 성공률로 비교하세요. " +
                        "MODE A 부터 확인하는 것이 순서입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "좌표 맞춤",
                subtitle = "마스터와 대상의 화면 비율이 다를 때 어떻게 할지.",
            ) {
                DropdownField(
                    label = "맞추는 방식",
                    options = FitMode.entries,
                    selected = settings.fitMode,
                    optionLabel = { it.koreanLabel },
                    onSelect = { f -> viewModel.updateSettings { it.copy(fitMode = f) } },
                )
            }

            SectionCard(title = "사용할 대상") {
                settings.enabledTargets.forEachIndexed { index, enabled ->
                    SwitchRow(
                        label = "TARGET ${index + 1}",
                        checked = enabled,
                        onCheckedChange = { value ->
                            viewModel.updateSettings { s ->
                                s.copy(
                                    enabledTargets = s.enabledTargets.toMutableList().also {
                                        it[index] = value
                                    },
                                )
                            }
                        },
                    )
                }
            }

            SectionCard(
                title = "수동 보정",
                subtitle = "자동 변환이 조금 어긋날 때 직접 맞춥니다.",
            ) {
                LabeledSlider(
                    label = "가로 배율",
                    value = settings.scaleX,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(scaleX = v) } },
                    valueRange = 0.5f..1.5f,
                    valueText = "%.2f".format(settings.scaleX),
                )
                LabeledSlider(
                    label = "세로 배율",
                    value = settings.scaleY,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(scaleY = v) } },
                    valueRange = 0.5f..1.5f,
                    valueText = "%.2f".format(settings.scaleY),
                )
                LabeledSlider(
                    label = "가로 이동",
                    value = settings.offsetX,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(offsetX = v) } },
                    valueRange = -200f..200f,
                    valueText = "${settings.offsetX.toInt()}px",
                )
                LabeledSlider(
                    label = "세로 이동",
                    value = settings.offsetY,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(offsetY = v) } },
                    valueRange = -200f..200f,
                    valueText = "${settings.offsetY.toInt()}px",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.updateSettings {
                            it.copy(scaleX = 1f, scaleY = 1f, offsetX = 0f, offsetY = 0f)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("보정값 초기화")
                }
            }

            SectionCard(title = "로그") {
                SwitchRow(
                    label = "로그를 파일로 저장",
                    checked = settings.saveLogToFile,
                    onCheckedChange = { v ->
                        viewModel.updateSettings { it.copy(saveLogToFile = v) }
                    },
                    helper = "좌표와 결과만 남깁니다. 개인정보는 저장하지 않습니다.",
                )
            }
        }
    }
}
