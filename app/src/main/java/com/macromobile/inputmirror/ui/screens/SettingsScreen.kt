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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.model.DispatchMode
import com.macromobile.inputmirror.model.FitMode
import com.macromobile.inputmirror.model.MirrorMode
import com.macromobile.inputmirror.MirrorApp
import com.macromobile.inputmirror.model.MirrorLayout
import com.macromobile.inputmirror.model.MirrorSettings
import com.macromobile.inputmirror.ui.MirrorViewModel

@Composable
fun SettingsScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val layout by MirrorApp.container().layoutRepository.layout
        .collectAsStateWithLifecycle(initialValue = MirrorLayout())

    // dp → px 환산은 반드시 실제 화면 밀도를 거친다. 픽셀 상수를 직접 쓰지 않는다.
    val density = LocalDensity.current.density
    val context = LocalContext.current
    // 안드로이드가 스스로 쓰는 터치 슬롭. 우리 임계값을 비교해 볼 기준으로만 보여준다.
    val touchSlopDp = remember(density) {
        val slopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop
        "%.1f".format(slopPx / density)
    }

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
                title = "동시 입력과 지연",
                subtitle = "이 설정이 무엇을 보장하고 무엇을 보장하지 않는지.",
            ) {
                Text(
                    "대상별 지연을 0ms 로 두어도 **하드웨어 수준의 완전한 동시 입력을 " +
                        "의미하지 않습니다.** 한 제스처에 손가락 여러 개를 담아 보내지만, " +
                        "시스템이 그것을 언제 어떤 순서로 풀어 각 창에 전달하는지는 앱이 " +
                        "정할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "실제로 어느 방식이 이 기기에서 통하는지는 홈 화면의 '동시 주입 측정' " +
                        "으로 숫자를 내어 확인하세요. 그 숫자가 나오기 전까지는 동시 입력이 " +
                        "된다고 가정하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "멀티터치",
                subtitle = "지금 이 방식에서 실제로 가능한 범위.",
            ) {
                val maxStrokes = capability.maxStrokeCount
                val targetCount = (layout.deliverableTargets.size +
                    (if (layout.master != null) 1 else 0)).coerceAtLeast(1)
                Text(
                    "단일 터치: 지원\n끌기 / 스와이프: 지원\n멀티터치(손가락 2개 이상): 지원하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (maxStrokes > 0) {
                    Text(
                        "이 기기는 한 제스처에 최대 ${maxStrokes}개의 스트로크를 담을 수 " +
                            "있습니다. 대상 하나당 손가락 하나가 스트로크 하나를 쓰므로, " +
                            "지금 대상 ${targetCount}개 기준으로 동시에 보낼 수 있는 손가락은 " +
                            "최대 ${maxStrokes / targetCount}개입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "접근성 서비스를 켜면 이 기기의 실제 스트로크 한계를 확인할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "입력을 받는 오버레이가 지금은 손가락 하나만 따라갑니다. 스트로크 예산이 " +
                        "남더라도 여러 손가락을 동시에 기록하지 않으므로, 되는 척하지 않고 " +
                        "미지원이라고 적어 둡니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "누르기 / 끌기 판정",
                subtitle = "DOWN 이후 움직인 거리로만 정합니다. 터치 순서는 보지 않습니다.",
            ) {
                LabeledSlider(
                    label = "끌기로 보는 최소 이동거리",
                    value = settings.dragThresholdDp,
                    onValueChange = { dp ->
                        viewModel.updateSettings { it.copy(dragThresholdDp = dp) }
                    },
                    valueRange = MirrorSettings.MIN_DRAG_THRESHOLD_DP..
                        MirrorSettings.MAX_DRAG_THRESHOLD_DP,
                    valueText = "${"%.0f".format(settings.dragThresholdDp)}dp" +
                        " (= ${"%.0f".format(settings.dragThresholdDp * density)}px)",
                    helper = "픽셀이 아니라 dp 입니다. 같은 픽셀 거리도 화면 밀도가 높으면 " +
                        "훨씬 짧은 거리라서, 픽셀로 고정하면 기기마다 판정이 달라집니다. " +
                        "이 기기의 밀도는 ${"%.2f".format(density)} 이고, 안드로이드 기본 " +
                        "터치 슬롭은 ${touchSlopDp}dp 입니다.",
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
