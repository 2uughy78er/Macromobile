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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.diag.CrashRecorder
import com.macromobile.inputmirror.ui.MirrorViewModel

/**
 * 메인 화면.
 *
 * 이 MVP 의 목적은 "검증"이므로, 무엇이 검증되었고 무엇이 아직 아닌지를
 * 화면 맨 위에 솔직하게 적어둔다.
 */
@Composable
fun MainScreen(
    viewModel: MirrorViewModel,
    onOpenTest: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenDiag: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // 앱이 죽은 적이 있으면 다음 실행 때 맨 위에 알린다. 사용자가 로그를 찾아다니지
    // 않아도 되도록, 원인을 볼 수 있는 곳으로 바로 보낸다.
    val crashReports = remember { CrashRecorder.reports() }

    LaunchedEffect(Unit) { viewModel.refreshCapability() }
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            MirrorTopBar("테스트 모드", onBack = onBack) {
                IconButton(onClick = onOpenLog) {
                    Icon(Icons.Default.Article, contentDescription = "로그")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "설정")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (crashReports.isNotEmpty()) {
                SectionCard(
                    title = "지난 실행에서 앱이 죽었습니다 (${crashReports.size}건)",
                    subtitle = "원인이 기록되어 있습니다.",
                    trailing = { TextButton(onClick = onOpenDiag) { Text("보기") } },
                ) {
                    val latest = crashReports.first()
                    Text(latest.headline, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "마지막 단계: ${latest.event} · ${latest.component} · 스레드 ${latest.threadName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(
                title = "이 앱은 검증용 MVP 입니다",
                subtitle = "본 프로젝트로 넘어가기 전에 '되는지'부터 확인합니다.",
            ) {
                Text(
                    "안드로이드는 화면에 보이지 않는 앱에 터치를 넣을 수 없습니다. " +
                        "dispatchGesture 는 앱이 아니라 화면 좌표에 입력을 주입하기 때문입니다.\n\n" +
                        "그래서 이 앱은 먼저 한 화면 안에 네 영역을 두고, MASTER 영역의 터치가 " +
                        "TARGET 1~3 에 실제로 주입되는지를 확인합니다. TARGET 에 그려지는 선은 " +
                        "시늉이 아니라 시스템이 실제로 전달한 터치입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(
                title = "권한",
                subtitle = "접근성 서비스가 없으면 입력을 넣을 수 없습니다.",
                trailing = { TextButton(onClick = onOpenPermission) { Text("설정") } },
            ) {
                StatusRow(
                    label = "접근성 서비스",
                    ok = capability.accessibilityConnected,
                    detail = if (capability.accessibilityConnected) {
                        "연결됨"
                    } else {
                        "꺼져 있습니다. 켜야 테스트할 수 있습니다."
                    },
                )
            }

            SectionCard(
                title = "이 기기가 지원하는 한계",
                subtitle = "시스템에 직접 물어본 값입니다.",
            ) {
                if (!capability.accessibilityConnected) {
                    Text(
                        "접근성 서비스를 켜면 이 기기의 실제 한계를 확인할 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "한 번에 보낼 수 있는 손가락 수: ${capability.maxStrokeCount}개",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "→ 동시에 조작할 수 있는 대상 수의 상한입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "제스처 하나의 최대 길이: ${capability.maxGestureDurationMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "→ 이보다 긴 드래그는 나눠서 보내야 합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(
                title = "현재 설정",
                trailing = { TextButton(onClick = onOpenSettings) { Text("변경") } },
            ) {
                Text("전송 방식: ${settings.mode.koreanLabel}", style = MaterialTheme.typography.bodyMedium)
                Text("추가 지연: ${settings.inputDelayMs}ms", style = MaterialTheme.typography.bodyMedium)
                Text("좌표 맞춤: ${settings.fitMode.koreanLabel}", style = MaterialTheme.typography.bodyMedium)
                val on = settings.enabledTargets.mapIndexedNotNull { i, e ->
                    if (e) "TARGET ${i + 1}" else null
                }
                Text(
                    "사용 대상: ${on.joinToString(", ").ifBlank { "없음" }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = onOpenTest,
                enabled = capability.accessibilityConnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("테스트 화면 열기")
            }
            if (!capability.accessibilityConnected) {
                Text(
                    "접근성 서비스를 켜야 열 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text("기록 보기")
            }
            OutlinedButton(onClick = onOpenDiag, modifier = Modifier.fillMaxWidth()) {
                Text("진단 · 크래시 기록")
            }
        }
    }
}
