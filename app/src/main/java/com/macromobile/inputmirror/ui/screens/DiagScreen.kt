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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.diag.CrashRecorder
import com.macromobile.inputmirror.diag.DiagLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/**
 * 진단 화면 — 크래시 보고서와 단계 기록을 앱 안에서 본다.
 *
 * PC 없이 기기만으로 원인을 확인할 수 있어야 한다는 요구에 맞춘 화면이다.
 * 크래시가 나면 앱이 죽지만, 죽기 직전에 남긴 보고서가 파일로 남아 다음 실행 때
 * 여기에 그대로 뜬다. 시각 / 스레드 / 컴포넌트 / 이벤트 / 예외 클래스 / 메시지 /
 * 스택 트레이스가 모두 들어 있다.
 */
@Composable
fun DiagScreen(onBack: () -> Unit) {
    val diagRecords by DiagLog.records.collectAsStateWithLifecycle()
    var reloadKey by remember { mutableStateOf(0) }
    val reports = remember(reloadKey) { CrashRecorder.reports() }
    var expanded by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = { MirrorTopBar("진단 · 크래시 기록", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "크래시 보고서 ${reports.size}건",
                subtitle = if (reports.isEmpty()) {
                    "저장된 크래시가 없습니다. 앱이 죽은 적이 없거나, 기록기를 설치하기 전에 죽은 것입니다."
                } else {
                    "가장 최근 것이 맨 위입니다. 눌러서 스택 트레이스를 펼칩니다."
                },
                trailing = {
                    if (reports.isNotEmpty()) {
                        TextButton(onClick = {
                            CrashRecorder.clear()
                            reloadKey++
                        }) { Text("지우기") }
                    }
                },
            ) {
                reports.forEach { report ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        STAMP.format(Date(report.timestampMs)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        report.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "스레드 ${report.threadName} · ${report.component} · ${report.event}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        expanded = if (expanded == report.fileName) null else report.fileName
                    }) {
                        Text(if (expanded == report.fileName) "접기 ▲" else "전체 보기 ▼")
                    }
                    if (expanded == report.fileName) {
                        Text(
                            report.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            SectionCard(
                title = "단계 기록",
                subtitle = "테스트 화면이 어디까지 진행됐는지. FAILED 가 있으면 그 줄이 원인입니다.",
                trailing = {
                    TextButton(onClick = { DiagLog.clear() }) { Text("지우기") }
                },
            ) {
                Text(
                    diagRecords.joinToString("\n") { it.line() }
                        .ifBlank { "아직 기록이 없습니다." },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            SectionCard(
                title = "파일 위치",
                subtitle = "PC 없이 기기 안에서 남는 곳입니다.",
            ) {
                Text(
                    "단계 기록: ${DiagLog.stageLogFile()?.absolutePath ?: "(초기화 전)"}\n" +
                        "크래시 보고서: 앱 내부 저장소 /crash/*.txt",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { reloadKey++ },
                    modifier = Modifier.weight(1f),
                ) { Text("새로고침") }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("돌아가기") }
            }
        }
    }
}
