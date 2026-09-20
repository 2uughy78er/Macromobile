package com.macromobile.imagemacro.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.findMainActivity

/**
 * 권한 안내 화면.
 *
 * 왜 이 권한이 필요한지 먼저 설명하고, 설정 화면으로 바로 보내준다.
 * 권한이 없는 상태에서는 매크로가 실행되지 않는다.
 */
@Composable
fun PermissionScreen(viewModel: MacroViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }

    Scaffold(topBar = { MacroTopBar("권한 설정", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "1. 접근성 서비스",
                subtitle = "화면을 대신 터치·스와이프하고 텍스트를 입력하기 위해 필요합니다.",
            ) {
                Text(
                    "루팅 없이 다른 앱을 조작할 수 있는 유일한 공식 방법입니다. " +
                        "이 앱은 매크로가 실행 중일 때만 화면을 조작하며, 읽은 화면 정보를 " +
                        "기기 밖으로 내보내지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    label = "상태",
                    ok = permissions.accessibilityEnabled,
                    detail = if (permissions.accessibilityEnabled) "연결됨" else "꺼져 있음",
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                viewModel.accessibilitySettingsIntent()
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            viewModel.showMessage("접근성 설정 화면을 열지 못했습니다.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("접근성 설정 열기")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "설정 → 접근성 → 설치된 서비스 → 'Image Macro 자동 조작' 을 켜주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "2. 화면 캡처",
                subtitle = "등록한 이미지를 화면에서 찾기 위해 필요합니다.",
            ) {
                Text(
                    "Android 의 화면 기록 권한(MediaProjection)을 사용합니다. " +
                        "캡처한 화면은 기기 안에서만 분석되고 서버로 전송되지 않습니다. " +
                        "기기를 재시작하거나 권한을 취소하면 다시 허용해야 합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    label = "상태",
                    ok = permissions.captureReady,
                    detail = if (permissions.captureReady) "허용됨" else "허용되지 않음",
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { activity?.requestScreenCapture() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (permissions.captureReady) "다시 허용하기" else "화면 캡처 허용")
                }
            }

            SectionCard(
                title = "3. 다른 앱 위에 표시 (선택)",
                subtitle = "실행 중 작은 컨트롤러를 띄우기 위해 필요합니다.",
            ) {
                Text(
                    "없어도 매크로는 동작합니다. 켜두면 다른 앱을 보는 중에도 " +
                        "매크로를 멈추거나 상태를 확인할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    label = "상태",
                    ok = permissions.overlayGranted,
                    detail = if (permissions.overlayGranted) "허용됨" else "허용되지 않음",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                viewModel.overlaySettingsIntent(context)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            viewModel.showMessage("설정 화면을 열지 못했습니다.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("오버레이 권한 설정")
                }
            }

            SectionCard(title = "이 앱이 하지 않는 일") {
                Text(
                    "• 루팅(ROOT) 권한을 요구하지 않습니다.\n" +
                        "• 화면 이미지를 외부 서버로 보내지 않습니다.\n" +
                        "• 매크로가 실행 중이 아닐 때는 화면을 조작하지 않습니다.\n" +
                        "• 어떤 앱이나 게임의 이미지도 기본 포함하지 않습니다. 모두 직접 등록합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
