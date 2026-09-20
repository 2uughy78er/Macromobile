package com.macromobile.imagemacro.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.Template
import com.macromobile.imagemacro.ui.MacroViewModel
import com.macromobile.imagemacro.ui.components.CropSelector
import com.macromobile.imagemacro.ui.components.NormalizedRect
import com.macromobile.imagemacro.ui.findMainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 이미지를 무엇으로 등록할지. */
enum class CaptureMode {
    /** 매크로 단계에서 찾을 버튼 이미지. */
    TEMPLATE,

    /** 발견 즉시 매크로를 멈출 타겟 카드. */
    TARGET,
    ;

    val title: String get() = if (this == TARGET) "타겟 카드 등록" else "이미지 등록"
    val itemName: String get() = if (this == TARGET) "타겟" else "이미지"
}

/**
 * 현재 화면(또는 갤러리 이미지)에서 원하는 영역을 잘라 템플릿으로 저장하는 화면.
 *
 * 앱이 화면을 덮고 있으면 등록하려는 버튼이 보이지 않으므로, 캡처 전에 앱을 잠깐 내렸다가
 * 다시 올린다. 저장되는 것은 **사용자가 고른 영역만** 이며 앱에는 어떤 기본 이미지도 없다.
 */
@Composable
fun ImageCaptureScreen(
    viewModel: MacroViewModel,
    macroId: String,
    mode: CaptureMode,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val scope = rememberCoroutineScope()
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    val macro = remember(macros, macroId) { macros.firstOrNull { it.id == macroId } }

    var selection by remember { mutableStateOf<NormalizedRect?>(null) }
    var name by remember { mutableStateOf("") }
    var threshold by remember {
        mutableFloatStateOf(
            if (mode == CaptureMode.TARGET) Target.DEFAULT_TARGET_THRESHOLD else Template.DEFAULT_THRESHOLD,
        )
    }
    var countdown by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            selection = null
            viewModel.loadImageFromUri(uri)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearSnapshot() }
    }

    // 카운트다운이 시작되면 앱을 내렸다가 캡처 후 다시 올린다.
    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        if (countdown == COUNTDOWN_SECONDS) activity?.minimizeApp()
        delay(1_000)
        if (countdown > 1) {
            countdown -= 1
        } else {
            countdown = 0
            viewModel.captureScreen { ok ->
                selection = null
                activity?.bringToFront()
                if (!ok) viewModel.showMessage("화면을 캡처하지 못했습니다.")
            }
        }
    }

    Scaffold(topBar = { MacroTopBar(mode.title, onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (macro == null) {
                Text("매크로를 찾을 수 없습니다.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            SectionCard(
                title = "1. 화면 가져오기",
                subtitle = "등록하려는 버튼이 보이는 화면을 준비하세요.",
            ) {
                if (!permissions.captureReady) {
                    Text(
                        "화면 캡처 권한이 없습니다. 권한을 먼저 허용하거나 갤러리 이미지를 사용하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { countdown = COUNTDOWN_SECONDS },
                        enabled = permissions.captureReady && countdown == 0 && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "${countdown}초 후 캡처" else "현재 화면 캡처")
                    }
                    OutlinedButton(
                        onClick = {
                            galleryPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("갤러리에서")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "'현재 화면 캡처'를 누르면 앱이 잠시 내려갑니다. " +
                        "${COUNTDOWN_SECONDS}초 안에 등록할 화면을 띄워주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val snap = snapshot
            if (busy || countdown > 0) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (snap == null) {
                SectionCard(title = "2. 영역 선택") {
                    Text(
                        "아직 화면이 없습니다. 위에서 화면을 캡처하거나 갤러리 이미지를 골라주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SectionCard(
                    title = "2. 영역 선택",
                    subtitle = "손가락으로 드래그해 ${mode.itemName}로 쓸 부분만 감싸주세요.",
                ) {
                    val bitmap = snap.bitmap
                    val ratio = if (bitmap.height > 0) {
                        bitmap.width.toFloat() / bitmap.height
                    } else {
                        1f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .background(Color.Black),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "캡처한 화면",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        CropSelector(
                            selection = selection,
                            onSelectionChange = { selection = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val px = selection?.toPixels(bitmap.width, bitmap.height)
                    Text(
                        if (px == null) {
                            "선택한 영역이 없습니다."
                        } else {
                            "선택 영역 ${px.width}×${px.height} (${px.x}, ${px.y})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (px != null) {
                        Spacer(Modifier.height(8.dp))
                        CropPreview(bitmap, px.x, px.y, px.width, px.height)
                    }
                }

                SectionCard(title = "3. 정보 입력") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("${mode.itemName} 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = "유사도 기준 (threshold)",
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = 0.5f..0.99f,
                        valueText = formatScore(threshold),
                        helper = "높을수록 정확히 같은 그림만 인식합니다. " +
                            "잘 못 찾으면 낮추고, 엉뚱한 곳을 찾으면 올리세요.",
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val rect = selection?.toPixels(snap.bitmap.width, snap.bitmap.height)
                            if (rect == null) {
                                viewModel.showMessage("먼저 영역을 드래그해서 선택해주세요.")
                                return@Button
                            }
                            saving = true
                            scope.launch {
                                val result = viewModel.saveCroppedTemplate(
                                    macro = macro,
                                    name = name.ifBlank { "${mode.itemName} ${System.currentTimeMillis() % 10000}" },
                                    cropX = rect.x,
                                    cropY = rect.y,
                                    cropW = rect.width,
                                    cropH = rect.height,
                                    threshold = threshold,
                                    asTarget = mode == CaptureMode.TARGET,
                                )
                                saving = false
                                if (result == null) {
                                    viewModel.showMessage("저장하지 못했습니다. 저장 공간을 확인해주세요.")
                                } else {
                                    viewModel.showMessage("${mode.itemName}를 등록했습니다.")
                                    onBack()
                                }
                            }
                        },
                        enabled = !saving && selection?.isUsable == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (saving) "저장 중..." else "${mode.itemName} 저장")
                    }
                }
            }
        }
    }
}

/** 잘라낸 부분만 확대해 보여준다. */
@Composable
private fun CropPreview(source: Bitmap, x: Int, y: Int, w: Int, h: Int) {
    val preview = remember(source, x, y, w, h) {
        runCatching {
            Bitmap.createBitmap(
                source,
                x.coerceIn(0, (source.width - 1).coerceAtLeast(0)),
                y.coerceIn(0, (source.height - 1).coerceAtLeast(0)),
                w.coerceIn(1, source.width - x.coerceIn(0, source.width - 1)),
                h.coerceIn(1, source.height - y.coerceIn(0, source.height - 1)),
            )
        }.getOrNull()
    }
    if (preview == null) return
    Column {
        Text("미리보기", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "선택 영역 미리보기",
            modifier = Modifier
                .height(96.dp)
                .background(Color.Black),
            contentScale = ContentScale.Fit,
        )
    }
}

private const val COUNTDOWN_SECONDS = 5
