package com.macromobile.inputmirror.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.ui.MirrorViewModel

@Composable
fun LogScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val records by viewModel.records.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = { MirrorTopBar("기록", onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "기록 ${records.size}개 · 실패 ${records.count { !it.success }}개",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    viewModel.logSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.clearLogs() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("화면 기록 지우기")
                    }
                    OutlinedButton(
                        onClick = { viewModel.deleteLogFiles() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("파일까지 지우기")
                    }
                }
            }

            if (records.isEmpty()) {
                Text(
                    "아직 기록이 없습니다. 테스트 화면에서 미러링을 켜고 MASTER 영역을 만져보세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = true,
                ) {
                    items(records.reversed()) { record ->
                        Text(
                            viewModel.formatRecord(record),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (record.success) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
