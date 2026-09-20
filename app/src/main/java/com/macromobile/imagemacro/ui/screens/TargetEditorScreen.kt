package com.macromobile.imagemacro.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.components.FileImage

/**
 * 타겟 카드 관리 화면.
 *
 * 타겟은 "이게 나오면 즉시 멈춰라" 라는 뜻이다. 앱에는 어떤 타겟 이미지도 들어 있지 않고,
 * 사용자가 화면 캡처나 갤러리에서 직접 등록한다.
 */
@Composable
fun TargetEditorScreen(
    viewModel: MacroViewModel,
    macroId: String,
    onBack: () -> Unit,
    onRegisterTarget: () -> Unit,
) {
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macro = remember(macros, macroId) { macros.firstOrNull { it.id == macroId } }
    var pendingDelete by remember { mutableStateOf<Target?>(null) }

    Scaffold(
        topBar = {
            MacroTopBar("타겟 카드", onBack) {
                IconButton(onClick = onRegisterTarget) {
                    Icon(Icons.Default.Add, contentDescription = "타겟 등록")
                }
            }
        },
    ) { padding ->
        if (macro == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("매크로를 찾을 수 없습니다.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }
        val settings = macro.targetSettings

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "등록한 타겟 (${macro.targets.size})",
                subtitle = "화면 캡처나 갤러리에서 원하는 카드를 잘라 등록하세요.",
                trailing = {
                    IconButton(onClick = onRegisterTarget) {
                        Icon(Icons.Default.Add, contentDescription = "타겟 등록")
                    }
                },
            ) {
                if (macro.targets.isEmpty()) {
                    Text(
                        "등록된 타겟이 없습니다. + 를 눌러 첫 타겟을 등록해보세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    macro.targets.forEach { target ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FileImage(
                                    file = viewModel.targetFile(macro.id, target),
                                    contentDescription = target.name,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    var name by remember(target.id) { mutableStateOf(target.name) }
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { value ->
                                            name = value
                                            viewModel.saveMacro(
                                                macro.copy(
                                                    targets = macro.targets.map {
                                                        if (it.id == target.id) it.copy(name = value) else it
                                                    },
                                                ),
                                            )
                                        },
                                        label = { Text("타겟 이름") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    LabeledSlider(
                                        label = "유사도 기준",
                                        value = target.threshold,
                                        onValueChange = { value ->
                                            viewModel.saveMacro(
                                                macro.copy(
                                                    targets = macro.targets.map {
                                                        if (it.id == target.id) it.copy(threshold = value) else it
                                                    },
                                                ),
                                            )
                                        },
                                        valueRange = 0.5f..0.99f,
                                        valueText = formatScore(target.threshold),
                                    )
                                    SwitchRow(
                                        label = "사용",
                                        checked = target.enabled,
                                        onCheckedChange = { value ->
                                            viewModel.saveMacro(
                                                macro.copy(
                                                    targets = macro.targets.map {
                                                        if (it.id == target.id) it.copy(enabled = value) else it
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                                IconButton(onClick = { pendingDelete = target }) {
                                    Icon(Icons.Default.Delete, contentDescription = "삭제")
                                }
                            }
                        }
                    }
                }
            }

            SectionCard(
                title = "판정 방식",
                subtitle = "등록한 타겟들을 어떻게 볼지 정합니다.",
            ) {
                DropdownField(
                    label = "판정",
                    options = TargetMatchMode.entries,
                    selected = settings.mode,
                    optionLabel = {
                        when (it) {
                            TargetMatchMode.ANY -> "하나라도 발견되면 성공 (ANY)"
                            TargetMatchMode.ALL -> "전부 발견되어야 성공 (ALL)"
                            TargetMatchMode.COUNT -> "N개 이상 발견되면 성공 (COUNT)"
                        }
                    },
                    onSelect = {
                        viewModel.saveMacro(macro.copy(targetSettings = settings.copy(mode = it)))
                    },
                )
                if (settings.mode == TargetMatchMode.COUNT) {
                    Spacer(Modifier.height(8.dp))
                    NumberField(
                        label = "최소 개수",
                        value = settings.minCount.toLong(),
                        onValueChange = {
                            viewModel.saveMacro(
                                macro.copy(
                                    targetSettings = settings.copy(
                                        minCount = it.toInt().coerceAtLeast(1),
                                    ),
                                ),
                            )
                        },
                        suffix = "개",
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = "공통 유사도 기준",
                    value = settings.threshold,
                    onValueChange = {
                        viewModel.saveMacro(
                            macro.copy(targetSettings = settings.copy(threshold = it)),
                        )
                    },
                    valueRange = 0.5f..0.99f,
                    valueText = formatScore(settings.threshold),
                    helper = "타겟별로 기준을 따로 정하지 않았을 때 이 값을 씁니다.",
                )
                NumberField(
                    label = "판정 전 대기 시간 (화면 전환 애니메이션 대기)",
                    value = settings.settleDelayMs,
                    onValueChange = {
                        viewModel.saveMacro(
                            macro.copy(targetSettings = settings.copy(settleDelayMs = it)),
                        )
                    },
                    suffix = "ms",
                )
            }

            SectionCard(
                title = "실시간 감시",
                subtitle = "매크로 단계와 별개로 화면을 계속 지켜보다가 타겟이 보이면 즉시 멈춥니다.",
            ) {
                SwitchRow(
                    label = "타겟 감시 켜기",
                    checked = settings.monitorEnabled,
                    onCheckedChange = {
                        viewModel.saveMacro(
                            macro.copy(targetSettings = settings.copy(monitorEnabled = it)),
                        )
                    },
                    helper = "켜면 배터리와 CPU 를 더 씁니다.",
                )
                if (settings.monitorEnabled) {
                    NumberField(
                        label = "감시 간격",
                        value = settings.monitorIntervalMs,
                        onValueChange = {
                            viewModel.saveMacro(
                                macro.copy(
                                    targetSettings = settings.copy(
                                        monitorIntervalMs = it.coerceAtLeast(100),
                                    ),
                                ),
                            )
                        },
                        suffix = "ms",
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField(
                        label = "연속 검출 횟수",
                        value = settings.requiredConsecutiveMatches.toLong(),
                        onValueChange = {
                            viewModel.saveMacro(
                                macro.copy(
                                    targetSettings = settings.copy(
                                        requiredConsecutiveMatches = it.toInt().coerceAtLeast(1),
                                    ),
                                ),
                            )
                        },
                        suffix = "회",
                    )
                    Text(
                        "1 이면 한 번만 보여도 바로 멈춥니다(즉시 중지 모드). " +
                            "2 이상으로 하면 짧은 간격으로 연속해서 보일 때만 멈춰 오탐이 줄어듭니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    label = "타겟 발견 시 화면 저장",
                    checked = settings.saveScreenshotOnFound,
                    onCheckedChange = {
                        viewModel.saveMacro(
                            macro.copy(targetSettings = settings.copy(saveScreenshotOnFound = it)),
                        )
                    },
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("타겟 삭제") },
            text = { Text("'${target.name.ifBlank { "이름 없음" }}' 타겟과 이미지를 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    macro?.let { viewModel.deleteTarget(it, target) {} }
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}
