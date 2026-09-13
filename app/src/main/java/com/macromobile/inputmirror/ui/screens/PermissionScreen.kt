package com.macromobile.inputmirror.ui.screens

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.inputmirror.ui.MirrorViewModel

@Composable
fun PermissionScreen(viewModel: MirrorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val capability by viewModel.capability.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshCapability() }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = { MirrorTopBar("권한", onBack) },
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
                title = "접근성 서비스",
                subtitle = "대상 영역에 터치를 넣기 위해 반드시 필요합니다.",
            ) {
                Text(
                    "루팅 없이 다른 창에 입력을 넣을 수 있는 유일한 공식 경로입니다. " +
                        "이 앱은 입력 주입에만 사용하며, 화면 내용을 읽거나 저장하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    label = "상태",
                    ok = capability.accessibilityConnected,
                    detail = if (capability.accessibilityConnected) "연결됨" else "꺼져 있음",
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                viewModel.accessibilitySettingsIntent()
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { viewModel.showMessage("접근성 설정 화면을 열지 못했습니다.") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("접근성 설정 열기")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "설정 → 접근성 → 설치된 서비스 → 'Input Mirror' 를 켜주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "이 MVP 가 쓰지 않는 것") {
                Text(
                    "• 화면 캡처(MediaProjection) — 이 검증에는 필요하지 않습니다.\n" +
                        "• 네트워크 — 권한을 요청하지 않습니다.\n" +
                        "• ROOT — 사용하지 않습니다.\n" +
                        "• ADB — 실행 중에 쓰지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
