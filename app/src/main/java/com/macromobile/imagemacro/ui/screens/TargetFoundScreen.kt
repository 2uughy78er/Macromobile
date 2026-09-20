package com.macromobile.imagemacro.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.automation.TargetFoundInfo
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.theme.successColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 타겟을 찾았을 때 보여주는 화면.
 *
 * 이 시점에 매크로는 이미 멈춰 있고, 추가 터치도 나가지 않는다.
 * 사용자가 결정할 때까지 상태를 그대로 유지한다.
 */
@Composable
fun TargetFoundScreen(
    viewModel: MacroViewModel,
    onClose: () -> Unit,
    onEditMacro: (String) -> Unit,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val info = status.targetFound
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macro = macros.firstOrNull { it.id == status.macroId }

    Scaffold(topBar = { MacroTopBar("타겟 발견", onClose) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (info == null) {
                Text("표시할 타겟 정보가 없습니다.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                return@Column
            }

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎯", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "TARGET FOUND",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = successColor(),
                )
                Spacer(Modifier.height(4.dp))
                Text("매크로가 중지되었습니다.", style = MaterialTheme.typography.bodyMedium)
            }

            SectionCard(title = "찾은 타겟") {
                Text(
                    info.targetName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "유사도 ${formatScore(info.score)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (info.matches.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    info.matches.forEach { m ->
                        Text(
                            "${if (m.match.found) "●" else "○"} " +
                                "${m.templateName.ifBlank { "이름 없음" }} — ${formatScore(m.match.score)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (info.screenshotPath != null) {
                SectionCard(
                    title = "발견 화면",
                    subtitle = if (settings.showMatchDebugOverlay) {
                        "찾은 위치를 사각형으로 표시했습니다."
                    } else {
                        "설정에서 '매칭 결과 표시'를 켜면 위치를 표시합니다."
                    },
                ) {
                    DebugScreenshot(
                        info = info,
                        showBoxes = settings.showMatchDebugOverlay,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("종료") }
                OutlinedButton(
                    onClick = {
                        viewModel.dismissTargetFound()
                        macro?.let { viewModel.startMacro(it) }
                        onClose()
                    },
                    enabled = macro != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("다시 실행")
                }
                OutlinedButton(
                    onClick = { macro?.let { onEditMacro(it.id) } },
                    enabled = macro != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("매크로 편집")
                }
            }
        }
    }
}

/** 저장된 화면 위에 매칭 위치를 그린다. */
@Composable
private fun DebugScreenshot(info: TargetFoundInfo, showBoxes: Boolean) {
    val path = info.screenshotPath ?: return
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                if (!f.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1080) sample *= 2
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Text(
            "화면을 불러오는 중입니다...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val ratio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(Color.Black),
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "타겟을 찾은 화면",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (showBoxes && info.screenWidth > 0 && info.screenHeight > 0) {
            val accent = successColor()
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / info.screenWidth
                val sy = size.height / info.screenHeight
                info.matches.filter { it.match.found }.forEach { m ->
                    drawRect(
                        color = accent,
                        topLeft = Offset(m.match.left * sx, m.match.top * sy),
                        size = Size(m.match.width * sx, m.match.height * sy),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
    }
}
