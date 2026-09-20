package com.macromobile.imagemacro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.ui.MacroViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 실행 기록. 앱을 다시 열었을 때 무슨 일이 있었는지 확인하는 화면. */
@Composable
fun RunLogScreen(viewModel: MacroViewModel, onBack: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Scaffold(topBar = { MacroTopBar("실행 기록", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(16.dp)) {
                SectionCard(title = "현재 상태") {
                    StatusRow(
                        label = status.state.koreanLabel,
                        ok = status.state != com.macromobile.imagemacro.automation.RunState.ERROR,
                        detail = buildString {
                            if (status.macroName.isNotBlank()) append(status.macroName).append(" · ")
                            append("단계 ${status.progressLabel} · 반복 ${status.cycleLabel}")
                        },
                    )
                    Text(
                        "성공 ${status.successCount}회 · 실패 ${status.failureCount}회 · " +
                            "실행 시간 ${formatDuration(status.elapsedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.errorMessage?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (status.log.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "아직 기록이 없습니다. 매크로를 실행하면 여기에 진행 상황이 남습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = true,
                ) {
                    items(status.log.reversed()) { entry ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                timeFormat.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = logLevelColor(entry.level),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
