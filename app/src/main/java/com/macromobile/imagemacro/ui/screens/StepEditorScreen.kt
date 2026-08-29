package com.macromobile.imagemacro.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.KeyAction
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.RefPoint
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.model.TemplateMatchMode
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.Routes
import com.macromobile.imagemacro.ui.components.FileImage

/**
 * 단계 하나를 만들거나 고치는 화면.
 *
 * 동작 종류를 고르면 그 종류에 필요한 설정만 보여준다.
 */
@Composable
fun StepEditorScreen(
    viewModel: MacroViewModel,
    macroId: String,
    stepId: String,
    onBack: () -> Unit,
) {
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macro = remember(macros, macroId) { macros.firstOrNull { it.id == macroId } }
    val existing = remember(macro, stepId) {
        if (stepId == Routes.NEW_STEP) null else macro?.steps?.firstOrNull { it.id == stepId }
    }
    var step by remember(existing?.id, stepId) {
        mutableStateOf(existing ?: MacroStep(afterDelayMs = 600L))
    }

    Scaffold(
        topBar = {
            MacroTopBar(if (existing == null) "단계 추가" else "단계 편집", onBack)
        },
    ) { padding ->
        if (macro == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("매크로를 찾을 수 없습니다.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "동작") {
                DropdownField(
                    label = "이 단계가 할 일",
                    options = ActionType.entries,
                    selected = step.type,
                    optionLabel = { it.koreanLabel },
                    onSelect = { step = step.copy(type = it) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = step.name,
                    onValueChange = { step = step.copy(name = it) },
                    label = { Text("단계 이름 (비워두면 자동)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    actionHelp(step.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (step.type.usesTemplates) {
                TemplateSection(
                    macro = macro,
                    viewModel = viewModel,
                    title = "찾을 이미지",
                    selectedIds = step.templateIds,
                    onToggle = { id ->
                        step = step.copy(
                            templateIds = if (id in step.templateIds) {
                                step.templateIds - id
                            } else {
                                step.templateIds + id
                            },
                        )
                    },
                )
                SectionCard(title = "이미지 판정") {
                    DropdownField(
                        label = "여러 이미지를 고른 경우",
                        options = TemplateMatchMode.entries,
                        selected = step.templateMatchMode,
                        optionLabel = { it.koreanLabel },
                        onSelect = { step = step.copy(templateMatchMode = it) },
                    )
                    if (step.templateMatchMode == TemplateMatchMode.COUNT) {
                        Spacer(Modifier.height(8.dp))
                        NumberField(
                            label = "최소 발견 개수",
                            value = step.minMatchCount.toLong(),
                            onValueChange = {
                                step = step.copy(minMatchCount = it.toInt().coerceAtLeast(1))
                            },
                            suffix = "개",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ThresholdOverride(
                        threshold = step.threshold,
                        onChange = { step = step.copy(threshold = it) },
                    )
                }
            }

            if (step.type == ActionType.TAP_UNTIL_IMAGE) {
                TemplateSection(
                    macro = macro,
                    viewModel = viewModel,
                    title = "이 이미지가 나오면 멈춤",
                    selectedIds = step.untilTemplateIds,
                    onToggle = { id ->
                        step = step.copy(
                            untilTemplateIds = if (id in step.untilTemplateIds) {
                                step.untilTemplateIds - id
                            } else {
                                step.untilTemplateIds + id
                            },
                        )
                    },
                )
            }

            if (step.type == ActionType.WAIT) {
                SectionCard(title = "대기 시간") {
                    NumberField(
                        label = "기다릴 시간",
                        value = step.waitMs,
                        onValueChange = { step = step.copy(waitMs = it) },
                        suffix = "ms",
                    )
                }
            }

            if (step.type in setOf(ActionType.TAP, ActionType.TAP_UNTIL_IMAGE, ActionType.TAP_UNTIL_IMAGE_DISAPPEARS)) {
                SectionCard(
                    title = "터치할 좌표",
                    subtitle = "기준 해상도(${macro.referenceWidth}×${macro.referenceHeight}) 기준입니다.",
                ) {
                    PointFields(
                        point = step.point ?: RefPoint(),
                        onChange = { step = step.copy(point = it) },
                    )
                }
            }

            if (step.type == ActionType.SWIPE) {
                SectionCard(title = "스와이프") {
                    Text("시작 위치", style = MaterialTheme.typography.labelLarge)
                    PointFields(
                        point = step.swipeStart ?: RefPoint(),
                        onChange = { step = step.copy(swipeStart = it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("끝 위치", style = MaterialTheme.typography.labelLarge)
                    PointFields(
                        point = step.swipeEnd ?: RefPoint(),
                        onChange = { step = step.copy(swipeEnd = it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    NumberField(
                        label = "스와이프에 걸리는 시간",
                        value = step.swipeDurationMs,
                        onValueChange = { step = step.copy(swipeDurationMs = it.coerceAtLeast(1)) },
                        suffix = "ms",
                    )
                }
            }

            if (step.type in setOf(ActionType.WAIT_AND_TAP, ActionType.TAP_IF_FOUND)) {
                SectionCard(
                    title = "클릭 위치 보정",
                    subtitle = "이미지 중앙이 실제 버튼이 아닐 때 조정합니다.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(
                            label = "가로 보정",
                            value = step.clickOffsetX.toLong(),
                            onValueChange = { step = step.copy(clickOffsetX = it.toInt()) },
                            suffix = "px",
                            allowNegative = true,
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = "세로 보정",
                            value = step.clickOffsetY.toLong(),
                            onValueChange = { step = step.copy(clickOffsetY = it.toInt()) },
                            suffix = "px",
                            allowNegative = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SwitchRow(
                        label = "이미지에 저장된 보정값도 함께 적용",
                        checked = step.useTemplateClickOffset,
                        onCheckedChange = { step = step.copy(useTemplateClickOffset = it) },
                    )
                }
            }

            if (step.type == ActionType.TEXT_INPUT) {
                SectionCard(
                    title = "입력할 텍스트",
                    subtitle = "@변수명, @random, @random:8, @randomnum:6 을 쓸 수 있습니다.",
                ) {
                    OutlinedTextField(
                        value = step.text,
                        onValueChange = { step = step.copy(text = it) },
                        label = { Text("텍스트") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(
                        label = "입력 전에 기존 내용 지우기",
                        checked = step.clearBeforeInput,
                        onCheckedChange = { step = step.copy(clearBeforeInput = it) },
                    )
                    Text(
                        "입력창을 먼저 터치하는 단계를 앞에 두어야 합니다. " +
                            "일부 앱은 자동 입력을 허용하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (step.type == ActionType.KEY_EVENT) {
                SectionCard(title = "키 동작") {
                    DropdownField(
                        label = "실행할 동작",
                        options = KeyAction.entries,
                        selected = step.keyAction,
                        optionLabel = { it.koreanLabel },
                        onSelect = { step = step.copy(keyAction = it) },
                    )
                }
            }

            if (step.type == ActionType.OCR) {
                SectionCard(
                    title = "글자 읽기",
                    subtitle = "지정한 영역의 글자를 읽어 변수에 저장합니다.",
                ) {
                    OutlinedTextField(
                        value = step.storeVariable,
                        onValueChange = { step = step.copy(storeVariable = it.trim()) },
                        label = { Text("저장할 변수 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = step.charset,
                        onValueChange = { step = step.copy(charset = it) },
                        label = { Text("허용할 문자 (비우면 제한 없음)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField(
                        label = "기대하는 글자 수 (0 이면 확인 안 함)",
                        value = step.expectedLength.toLong(),
                        onValueChange = { step = step.copy(expectedLength = it.toInt()) },
                        suffix = "자",
                    )
                    SwitchRow(
                        label = "한국어 인식기 사용",
                        checked = step.ocrKorean,
                        onCheckedChange = { step = step.copy(ocrKorean = it) },
                        helper = "끄면 영문·숫자 인식기를 씁니다(짧은 코드에 더 정확합니다).",
                    )
                }
            }

            if (step.type == ActionType.TARGET_CHECK) {
                SectionCard(
                    title = "타겟 검사",
                    subtitle = "비워두면 매크로에 등록된 모든 타겟을 검사합니다.",
                ) {
                    if (macro.targets.isEmpty()) {
                        Text(
                            "등록된 타겟이 없습니다. 매크로 편집 화면에서 먼저 타겟을 등록해주세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        macro.targets.forEach { target ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        step = step.copy(
                                            targetIds = if (target.id in step.targetIds) {
                                                step.targetIds - target.id
                                            } else {
                                                step.targetIds + target.id
                                            },
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = target.id in step.targetIds, onCheckedChange = null)
                                Spacer(Modifier.height(0.dp))
                                Text("  ${target.name.ifBlank { "이름 없음" }}")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DropdownField(
                        label = "판정 방식 (비우면 매크로 설정을 따름)",
                        options = TargetMatchMode.entries,
                        selected = step.targetMatchMode ?: macro.targetSettings.mode,
                        optionLabel = {
                            when (it) {
                                TargetMatchMode.ANY -> "하나라도 (ANY)"
                                TargetMatchMode.ALL -> "모두 (ALL)"
                                TargetMatchMode.COUNT -> "N개 이상 (COUNT)"
                            }
                        },
                        onSelect = { step = step.copy(targetMatchMode = it) },
                    )
                    if ((step.targetMatchMode ?: macro.targetSettings.mode) == TargetMatchMode.COUNT) {
                        Spacer(Modifier.height(8.dp))
                        NumberField(
                            label = "최소 발견 개수",
                            value = step.targetMinCount.toLong(),
                            onValueChange = {
                                step = step.copy(targetMinCount = it.toInt().coerceAtLeast(1))
                            },
                            suffix = "개",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DropdownField(
                        label = "타겟을 못 찾았을 때",
                        options = OnTimeout.entries,
                        selected = step.onTargetMissing,
                        optionLabel = { it.koreanLabel },
                        onSelect = { step = step.copy(onTargetMissing = it) },
                    )
                }
            }

            // 이미지·OCR 단계는 시간·ROI 설정이 필요하다.
            if (step.type.usesTemplates || step.type == ActionType.OCR) {
                SectionCard(title = "시간 설정") {
                    NumberField(
                        label = "최대 기다릴 시간 (timeout)",
                        value = step.timeoutMs,
                        onValueChange = { step = step.copy(timeoutMs = it) },
                        suffix = "ms",
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField(
                        label = "화면 검사 간격 (0 이면 전역 설정)",
                        value = step.pollIntervalMs,
                        onValueChange = { step = step.copy(pollIntervalMs = it) },
                        suffix = "ms",
                    )
                    if (step.type == ActionType.TAP_UNTIL_IMAGE ||
                        step.type == ActionType.TAP_UNTIL_IMAGE_DISAPPEARS
                    ) {
                        Spacer(Modifier.height(8.dp))
                        NumberField(
                            label = "연타 간격",
                            value = step.repeatIntervalMs,
                            onValueChange = { step = step.copy(repeatIntervalMs = it) },
                            suffix = "ms",
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    DropdownField(
                        label = "시간이 지나도 못 찾으면",
                        options = OnTimeout.entries,
                        selected = step.onTimeout,
                        optionLabel = { it.koreanLabel },
                        onSelect = { step = step.copy(onTimeout = it) },
                    )
                }

                RoiSection(
                    macro = macro,
                    roi = step.roi,
                    onChange = { step = step.copy(roi = it) },
                    required = step.type == ActionType.OCR,
                )
            }

            SectionCard(title = "마무리") {
                NumberField(
                    label = "단계가 끝난 뒤 쉬는 시간",
                    value = step.afterDelayMs,
                    onValueChange = { step = step.copy(afterDelayMs = it) },
                    suffix = "ms",
                )
                SwitchRow(
                    label = "이 단계 사용",
                    checked = step.enabled,
                    onCheckedChange = { step = step.copy(enabled = it) },
                )
            }

            Button(
                onClick = {
                    viewModel.upsertStep(macro, step) { onBack() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("단계 저장")
            }
        }
    }
}

@Composable
private fun TemplateSection(
    macro: Macro,
    viewModel: MacroViewModel,
    title: String,
    selectedIds: List<String>,
    onToggle: (String) -> Unit,
) {
    SectionCard(title = title, subtitle = "등록한 이미지 중에서 고르세요.") {
        if (macro.templates.isEmpty()) {
            Text(
                "등록한 이미지가 없습니다. 매크로 편집 화면에서 먼저 이미지를 등록해주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@SectionCard
        }
        macro.templates.forEach { template ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(template.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = template.id in selectedIds, onCheckedChange = null)
                Spacer(Modifier.height(0.dp))
                FileImage(
                    file = viewModel.templateFile(macro.id, template),
                    contentDescription = template.name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(template.name.ifBlank { "이름 없음" })
                    Text(
                        "기준 ${formatScore(template.threshold)} · " +
                            "${template.referenceScreenWidth}×${template.referenceScreenHeight}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThresholdOverride(threshold: Float?, onChange: (Float?) -> Unit) {
    var enabled by remember(threshold) { mutableStateOf(threshold != null) }
    SwitchRow(
        label = "이 단계에서 유사도 기준 따로 쓰기",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            onChange(if (it) (threshold ?: 0.85f) else null)
        },
        helper = "끄면 이미지에 저장된 기준값을 씁니다.",
    )
    if (enabled) {
        LabeledSlider(
            label = "유사도 기준",
            value = threshold ?: 0.85f,
            onValueChange = { onChange(it) },
            valueRange = 0.5f..0.99f,
            valueText = formatScore(threshold ?: 0.85f),
        )
    }
}

@Composable
private fun PointFields(point: RefPoint, onChange: (RefPoint) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            label = "X",
            value = point.x.toLong(),
            onValueChange = { onChange(point.copy(x = it.toInt())) },
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "Y",
            value = point.y.toLong(),
            onValueChange = { onChange(point.copy(y = it.toInt())) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RoiSection(macro: Macro, roi: Roi?, onChange: (Roi?) -> Unit, required: Boolean) {
    SectionCard(
        title = if (required) "읽을 영역 (필수)" else "검색 영역 (선택)",
        subtitle = "화면 전체 대신 일부만 보면 훨씬 빠릅니다. " +
            "기준 해상도 ${macro.referenceWidth}×${macro.referenceHeight} 좌표입니다.",
    ) {
        var enabled by remember(roi) { mutableStateOf(roi != null) }
        if (!required) {
            SwitchRow(
                label = "영역 지정",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    onChange(if (it) (roi ?: Roi(0, 0, macro.referenceWidth, macro.referenceHeight)) else null)
                },
            )
        }
        if (enabled || required) {
            val current = roi ?: Roi(0, 0, macro.referenceWidth, macro.referenceHeight)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "X",
                    value = current.x.toLong(),
                    onValueChange = { onChange(current.copy(x = it.toInt())) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Y",
                    value = current.y.toLong(),
                    onValueChange = { onChange(current.copy(y = it.toInt())) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "너비",
                    value = current.width.toLong(),
                    onValueChange = { onChange(current.copy(width = it.toInt())) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "높이",
                    value = current.height.toLong(),
                    onValueChange = { onChange(current.copy(height = it.toInt())) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onChange(Roi(0, 0, macro.referenceWidth, macro.referenceHeight)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("화면 전체로 되돌리기")
            }
        }
    }
}

/** 동작 종류별 한 줄 설명. */
private fun actionHelp(type: ActionType): String = when (type) {
    ActionType.WAIT -> "정해진 시간만큼 그냥 기다립니다."
    ActionType.TAP -> "이미지와 관계없이 지정한 좌표를 한 번 누릅니다."
    ActionType.WAIT_FOR_IMAGE -> "이미지가 나타날 때까지 기다리기만 합니다(누르지 않음)."
    ActionType.WAIT_AND_TAP -> "이미지가 나타나면 발견된 그 위치를 누릅니다. 가장 많이 쓰는 동작입니다."
    ActionType.TAP_IF_FOUND -> "잠깐만 확인해서 이미지가 있으면 누르고, 없으면 그냥 넘어갑니다. 팝업 처리에 좋습니다."
    ActionType.TAP_UNTIL_IMAGE -> "목표 이미지가 나올 때까지 지정한 위치를 계속 누릅니다. 대사 넘기기에 좋습니다."
    ActionType.TAP_UNTIL_IMAGE_DISAPPEARS -> "지정한 이미지가 사라질 때까지 계속 누릅니다."
    ActionType.SWIPE -> "한 지점에서 다른 지점으로 밀어 넘깁니다."
    ActionType.TEXT_INPUT -> "입력창에 글자를 자동으로 넣습니다."
    ActionType.KEY_EVENT -> "뒤로가기·홈 같은 시스템 동작을 실행합니다."
    ActionType.SCREENSHOT -> "지금 화면을 파일로 저장합니다."
    ActionType.OCR -> "지정한 영역의 글자를 읽어 변수에 담습니다. 이후 텍스트 입력에서 쓸 수 있습니다."
    ActionType.TARGET_CHECK -> "등록한 타겟이 화면에 있는지 확인하고, 있으면 매크로를 멈춥니다."
    ActionType.STOP_MACRO -> "여기서 매크로를 끝냅니다."
}
