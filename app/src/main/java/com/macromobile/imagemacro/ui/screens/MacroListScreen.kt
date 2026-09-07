package com.macromobile.imagemacro.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.ui.MacroViewModel
import kotlinx.coroutines.launch

/** 만들어 둔 매크로 목록. 처음에는 비어 있다. */
@Composable
fun MacroListScreen(
    viewModel: MacroViewModel,
    onBack: () -> Unit,
    onOpenMacro: (String) -> Unit,
) {
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 내보낸 백업 파일에서 매크로를 되살린다.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importMacro(uri) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Macro?>(null) }

    // 새 매크로의 기준 해상도로 쓸 현재 화면 크기(픽셀).
    val (screenWidth, screenHeight) = remember { viewModel.currentScreenSize() }

    Scaffold(
        topBar = {
            MacroTopBar("매크로 목록", onBack) {
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileDownload, contentDescription = "백업 파일에서 가져오기")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("새 매크로") },
            )
        },
    ) { padding ->
        if (macros.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("아직 만든 매크로가 없습니다.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "'새 매크로' 를 눌러 시작하세요.\n" +
                        "화면을 캡처해 버튼 이미지를 등록하고, 원하는 순서로 단계를 쌓으면 됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("백업 파일에서 가져오기")
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(macros, key = { it.id }) { macro ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMacro(macro.id) },
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(macro.displayName(), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "단계 ${macro.steps.size} · 이미지 ${macro.templates.size} · " +
                                        "타겟 ${macro.targets.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (macro.referenceWidth > 0) {
                                    Text(
                                        "기준 해상도 ${macro.referenceWidth}×${macro.referenceHeight}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.duplicateMacro(macro) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "복제")
                            }
                            IconButton(onClick = { pendingDelete = macro }) {
                                Icon(Icons.Default.Delete, contentDescription = "삭제")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("새 매크로") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("매크로 이름") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "기준 해상도는 현재 화면(${screenWidth}×${screenHeight})으로 저장됩니다. " +
                            "다른 해상도 기기에서도 자동 보정됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val macroName = name.ifBlank { "새 매크로" }
                    showCreate = false
                    scope.launch {
                        val created = viewModel.createMacro(macroName, screenWidth, screenHeight)
                        onOpenMacro(created.id)
                    }
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("취소") }
            },
        )
    }

    pendingDelete?.let { macro ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("매크로 삭제") },
            text = {
                Text("'${macro.displayName()}' 과(와) 등록한 이미지가 모두 삭제됩니다. 되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMacro(macro)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}
