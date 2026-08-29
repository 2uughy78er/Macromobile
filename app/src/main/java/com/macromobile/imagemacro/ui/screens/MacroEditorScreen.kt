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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.Template
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.components.FileImage

/**
 * 매크로 편집 화면.
 *
 * 단계 목록, 등록한 이미지, 반복 설정, 타겟 설정으로 넘어가는 입구를 모아둔다.
 * 단계의 실제 내용은 전부 사용자가 만든다 — 기본 단계는 하나도 들어 있지 않다.
 */
@Composable
fun MacroEditorScreen(
    viewModel: MacroViewModel,
    macroId: String,
    onBack: () -> Unit,
    onAddStep: () -> Unit,
    onEditStep: (MacroStep) -> Unit,
    onRegisterTemplate: () -> Unit,
    onOpenTargets: () -> Unit,
) {
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macro = remember(macros, macroId) { macros.firstOrNull { it.id == macroId } }
    var pendingTemplateDelete by remember { mutableStateOf<Template?>(null) }
    var pendingStepDelete by remember { mutableStateOf<MacroStep?>(null) }

    Scaffold(topBar = { MacroTopBar(macro?.displayName() ?: "매크로 편집", onBack) }) { padding ->
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
            SectionCard(title = "기본 정보") {
                var name by remember(macro.id) { mutableStateOf(macro.name) }
                // 글자를 칠 때마다 파일에 쓰지 않도록 잠깐 멈췄을 때만 저장한다.
                LaunchedEffect(name) {
                    if (name == macro.name) return@LaunchedEffect
                    delay(500)
                    viewModel.saveMacro(macro.copy(name = name))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("매크로 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "기준 해상도 ${macro.referenceWidth}×${macro.referenceHeight} · " +
                        "다른 해상도 기기에서 자동 보정됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "등록한 이미지 (${macro.templates.size})",
                subtitle = "화면에서 찾을 버튼 이미지입니다.",
                trailing = {
                    IconButton(onClick = onRegisterTemplate) {
                        Icon(Icons.Default.Add, contentDescription = "이미지 등록")
                    }
                },
            ) {
                if (macro.templates.isEmpty()) {
                    Text(
                        "아직 등록한 이미지가 없습니다. + 를 눌러 현재 화면에서 버튼을 잘라내세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(macro.templates.size) { index ->
                            val template = macro.templates[index]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FileImage(
                                    file = viewModel.templateFile(macro.id, template),
                                    contentDescription = template.name,
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    template.name.ifBlank { "이름 없음" },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    formatScore(template.threshold),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { pendingTemplateDelete = template }) {
                                    Text("삭제", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            SectionCard(
                title = "단계 (${macro.steps.size})",
                subtitle = "위에서 아래 순서로 실행됩니다.",
                trailing = {
                    IconButton(onClick = onAddStep) {
                        Icon(Icons.Default.Add, contentDescription = "단계 추가")
                    }
                },
            ) {
                if (macro.steps.isEmpty()) {
                    Text(
                        "단계가 없습니다. + 를 눌러 첫 단계를 만들어보세요.\n" +
                            "예: '이미지 발견 후 터치' → '2초 대기' → '타겟 검사'",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    macro.steps.forEachIndexed { index, step ->
                        StepRow(
                            macro = macro,
                            step = step,
                            index = index,
                            isFirst = index == 0,
                            isLast = index == macro.steps.lastIndex,
                            onClick = { onEditStep(step) },
                            onToggle = { enabled ->
                                viewModel.upsertStep(macro, step.copy(enabled = enabled)) {}
                            },
                            onMoveUp = { viewModel.moveStep(macro, index, index - 1) {} },
                            onMoveDown = { viewModel.moveStep(macro, index, index + 1) {} },
                            onDuplicate = { viewModel.duplicateStep(macro, step) {} },
                            onDelete = { pendingStepDelete = step },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            SectionCard(
                title = "타겟 카드 (${macro.targets.size})",
                subtitle = "화면에 나타나면 매크로를 즉시 멈춥니다.",
                trailing = { TextButton(onClick = onOpenTargets) { Text("관리") } },
            ) {
                if (macro.targets.isEmpty()) {
                    Text(
                        "등록한 타겟이 없습니다. '관리' 에서 원하는 카드 이미지를 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(macro.targets.size) { index ->
                            val target = macro.targets[index]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FileImage(
                                    file = viewModel.targetFile(macro.id, target),
                                    contentDescription = target.name,
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    target.name.ifBlank { "이름 없음" },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "판정 방식: ${macro.targetSettings.mode.name} · " +
                            (if (macro.targetSettings.monitorEnabled) "실시간 감시 켜짐" else "실시간 감시 꺼짐"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RepeatSection(macro = macro, viewModel = viewModel)
        }
    }

    pendingTemplateDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingTemplateDelete = null },
            title = { Text("이미지 삭제") },
            text = {
                Text(
                    "'${template.name.ifBlank { "이름 없음" }}' 이미지를 삭제합니다. " +
                        "이 이미지를 쓰던 단계에서도 참조가 사라집니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    macro?.let { viewModel.deleteTemplate(it, template) {} }
                    pendingTemplateDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplateDelete = null }) { Text("취소") }
            },
        )
    }

    pendingStepDelete?.let { step ->
        AlertDialog(
            onDismissRequest = { pendingStepDelete = null },
            title = { Text("단계 삭제") },
            text = { Text("이 단계를 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    macro?.let { viewModel.removeStep(it, step) {} }
                    pendingStepDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingStepDelete = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun StepRow(
    macro: Macro,
    step: MacroStep,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (step.enabled) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = step.enabled, onCheckedChange = onToggle)
                Column(Modifier.weight(1f)) {
                    Text(
                        step.displayName(index),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stepSummary(macro, step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "위로")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "아래로")
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "복제")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "삭제")
                }
            }
        }
    }
}

/** 단계 한 줄에 보여줄 요약. */
private fun stepSummary(macro: Macro, step: MacroStep): String = buildString {
    append(step.type.koreanLabel)
    if (step.type.usesTemplates) {
        val names = step.templateIds.mapNotNull { macro.template(it)?.name }
        append(" · 이미지 ")
        append(if (names.isEmpty()) "없음" else names.joinToString(", ") { it.ifBlank { "이름없음" } })
    }
    when (step.type) {
        ActionType.WAIT -> append(" · ${step.waitMs}ms")
        ActionType.TAP ->
            append(" · (${step.point?.x ?: 0}, ${step.point?.y ?: 0})")
        ActionType.TEXT_INPUT ->
            append(" · '${step.text.take(16)}'")
        ActionType.OCR ->
            append(" · @${step.storeVariable}")
        else -> Unit
    }
}

@Composable
private fun RepeatSection(macro: Macro, viewModel: MacroViewModel) {
    val repeat = macro.repeat
    SectionCard(title = "반복 설정") {
        SwitchRow(
            label = "무한 반복",
            checked = repeat.isInfinite,
            onCheckedChange = { infinite ->
                viewModel.saveMacro(
                    macro.copy(repeat = repeat.copy(count = if (infinite) -1 else 1)),
                )
            },
            helper = "끄면 정해진 횟수만 실행합니다.",
        )
        if (!repeat.isInfinite) {
            NumberField(
                label = "반복 횟수",
                value = repeat.count.toLong(),
                onValueChange = { value ->
                    viewModel.saveMacro(
                        macro.copy(repeat = repeat.copy(count = value.toInt().coerceAtLeast(1))),
                    )
                },
                suffix = "회",
            )
            Spacer(Modifier.height(8.dp))
        }
        SwitchRow(
            label = "한 바퀴 성공하면 중지",
            checked = repeat.stopOnSuccess,
            onCheckedChange = {
                viewModel.saveMacro(macro.copy(repeat = repeat.copy(stopOnSuccess = it)))
            },
        )
        SwitchRow(
            label = "타겟을 찾으면 중지",
            checked = repeat.stopOnTargetFound,
            onCheckedChange = {
                viewModel.saveMacro(macro.copy(repeat = repeat.copy(stopOnTargetFound = it)))
            },
            helper = "타겟 검사에 성공하면 언제나 매크로가 멈춥니다.",
        )
        SwitchRow(
            label = "실패하면 처음부터 다시",
            checked = repeat.restartOnFailure,
            onCheckedChange = {
                viewModel.saveMacro(macro.copy(repeat = repeat.copy(restartOnFailure = it)))
            },
            helper = "끄면 단계가 실패했을 때 매크로를 중단합니다.",
        )
        NumberField(
            label = "한 바퀴 끝난 뒤 쉬는 시간",
            value = repeat.cycleDelayMs,
            onValueChange = {
                viewModel.saveMacro(macro.copy(repeat = repeat.copy(cycleDelayMs = it)))
            },
            suffix = "ms",
        )
    }
}
