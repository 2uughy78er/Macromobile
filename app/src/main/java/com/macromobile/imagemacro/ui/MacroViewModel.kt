package com.macromobile.imagemacro.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromobile.imagemacro.MacroApp
import com.macromobile.imagemacro.automation.MacroStatus
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.Template
import com.macromobile.imagemacro.service.CaptureResult
import com.macromobile.imagemacro.service.MacroAccessibilityService
import com.macromobile.imagemacro.service.MacroController
import com.macromobile.imagemacro.service.OverlayController
import com.macromobile.imagemacro.storage.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 화면 캡처로 받아온, 사용자가 영역을 고를 이미지. */
data class CaptureSnapshot(
    val bitmap: Bitmap,
    val screenWidth: Int,
    val screenHeight: Int,
)

/** 권한 상태 한눈에 보기. */
data class PermissionState(
    val accessibilityEnabled: Boolean = false,
    val captureReady: Boolean = false,
    val overlayGranted: Boolean = false,
    val openCvReady: Boolean = false,
) {
    /** 매크로를 실행할 수 있는 최소 조건. */
    val canRun: Boolean get() = accessibilityEnabled && captureReady && openCvReady
}

class MacroViewModel(app: Application) : AndroidViewModel(app) {

    private val container = MacroApp.container()

    val macros: StateFlow<List<Macro>> = container.macroRepository.macros

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val status: StateFlow<MacroStatus> = MacroController.status

    val serviceError: StateFlow<String?> = MacroController.serviceError

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _snapshot = MutableStateFlow<CaptureSnapshot?>(null)
    val snapshot: StateFlow<CaptureSnapshot?> = _snapshot.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch { container.macroRepository.refresh() }
        viewModelScope.launch {
            MacroAccessibilityService.connected.collect { refreshPermissions() }
        }
    }

    /**
     * 매크로의 기준 해상도로 쓸 화면 크기.
     *
     * 좌표와 ROI 는 캡처한 화면 위에서 고르므로, 캡처가 준비돼 있으면 그 크기를 쓴다.
     * 아직 캡처 권한이 없으면 기기의 실제 화면 크기로 대신한다.
     */
    fun currentScreenSize(): Pair<Int, Int> {
        MacroController.capture.value?.let { capture ->
            if (capture.active.value && capture.screenWidth > 0) {
                return capture.screenWidth to capture.screenHeight
            }
        }
        val context = getApplication<Application>()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (wm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            if (bounds.width() > 0) return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _permissions.value = PermissionState(
            accessibilityEnabled = MacroAccessibilityService.isConnected ||
                MacroAccessibilityService.isEnabledInSettings(context),
            captureReady = MacroController.isCaptureReady,
            overlayGranted = OverlayController.canDrawOverlays(context),
            openCvReady = MacroApp.openCvReady,
        )
    }

    fun showMessage(text: String?) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
        MacroController.reportError(null)
    }

    // ------------------------------------------------------------------
    // 매크로 CRUD
    // ------------------------------------------------------------------

    suspend fun createMacro(name: String, screenWidth: Int, screenHeight: Int): Macro {
        val macro = Macro(
            name = name,
            referenceWidth = screenWidth,
            referenceHeight = screenHeight,
        )
        return container.macroRepository.save(macro)
    }

    fun saveMacro(macro: Macro, onSaved: (Macro) -> Unit = {}) {
        viewModelScope.launch {
            val saved = container.macroRepository.save(macro)
            container.invalidateImageCache()
            onSaved(saved)
        }
    }

    fun deleteMacro(macro: Macro) {
        viewModelScope.launch {
            container.macroRepository.delete(macro.id)
            container.invalidateImageCache()
            showMessage("'${macro.displayName()}' 을(를) 삭제했습니다.")
        }
    }

    fun duplicateMacro(macro: Macro) {
        viewModelScope.launch {
            container.macroRepository.duplicate(macro)
            showMessage("매크로를 복제했습니다.")
        }
    }

    fun macro(id: String): Macro? = container.macroRepository.get(id)

    fun exportMacroJson(macro: Macro): String = container.macroRepository.exportJson(macro)

    // ------------------------------------------------------------------
    // 이미지 등록
    // ------------------------------------------------------------------

    /** 현재 화면을 캡처해 영역 선택 화면에서 쓸 이미지를 만든다. */
    fun captureScreen(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val capture = MacroController.capture.value
                if (capture == null || !capture.active.value) {
                    showMessage("화면 캡처 권한이 필요합니다. 먼저 화면 캡처를 허용해주세요.")
                    onDone(false)
                    return@launch
                }
                when (val result = capture.captureStandalone()) {
                    is CaptureResult.Ok -> {
                        clearSnapshot()
                        _snapshot.value = CaptureSnapshot(
                            bitmap = result.frame.bitmap,
                            screenWidth = result.frame.width,
                            screenHeight = result.frame.height,
                        )
                        // Mat 캐시는 필요 없으므로 바로 비운다(Bitmap 은 UI 가 계속 쓴다).
                        result.frame.release()
                        onDone(true)
                    }

                    is CaptureResult.Error -> {
                        showMessage(result.message)
                        onDone(false)
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** 갤러리에서 고른 이미지를 영역 선택 화면으로 넘긴다. */
    fun loadImageFromUri(uri: Uri, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeUri(getApplication(), uri) }
                if (bitmap == null) {
                    showMessage("이미지를 읽지 못했습니다. 다른 이미지를 선택해주세요.")
                    onDone(false)
                } else {
                    clearSnapshot()
                    _snapshot.value = CaptureSnapshot(bitmap, bitmap.width, bitmap.height)
                    onDone(true)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearSnapshot() {
        _snapshot.value?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        _snapshot.value = null
    }

    /**
     * 잘라낸 영역을 템플릿(또는 타겟)으로 저장한다.
     *
     * @return 저장된 Template / Target. 실패하면 null.
     */
    suspend fun saveCroppedTemplate(
        macro: Macro,
        name: String,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        threshold: Float,
        asTarget: Boolean,
    ): Pair<Macro, String>? = withContext(Dispatchers.IO) {
        val snap = _snapshot.value ?: return@withContext null
        val source = snap.bitmap
        if (source.isRecycled) return@withContext null

        val x = cropX.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val y = cropY.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val w = cropW.coerceIn(1, source.width - x)
        val h = cropH.coerceIn(1, source.height - y)

        val cropped = try {
            Bitmap.createBitmap(source, x, y, w, h)
        } catch (e: Exception) {
            null
        } ?: return@withContext null

        val fileName = container.files.saveTemplateImage(macro.id, cropped, asTarget)
        cropped.recycle()
        if (fileName == null) return@withContext null

        val updated = if (asTarget) {
            val target = Target(
                name = name,
                fileName = fileName,
                referenceScreenWidth = snap.screenWidth,
                referenceScreenHeight = snap.screenHeight,
                threshold = threshold,
                createdAt = System.currentTimeMillis(),
            )
            macro.copy(targets = macro.targets + target) to target.id
        } else {
            val template = Template(
                name = name,
                fileName = fileName,
                referenceScreenWidth = snap.screenWidth,
                referenceScreenHeight = snap.screenHeight,
                sourceRegion = com.macromobile.imagemacro.model.Roi(x, y, w, h),
                threshold = threshold,
                createdAt = System.currentTimeMillis(),
            )
            macro.copy(templates = macro.templates + template) to template.id
        }
        val saved = container.macroRepository.save(updated.first)
        container.invalidateImageCache()
        saved to updated.second
    }

    fun deleteTemplate(macro: Macro, template: Template, onSaved: (Macro) -> Unit) {
        viewModelScope.launch {
            container.files.deleteTemplateImage(macro.id, template.fileName, asTarget = false)
            // 이 템플릿을 쓰던 단계에서도 참조를 빼준다.
            val steps = macro.steps.map { step ->
                step.copy(
                    templateIds = step.templateIds - template.id,
                    untilTemplateIds = step.untilTemplateIds - template.id,
                )
            }
            val saved = container.macroRepository.save(
                macro.copy(templates = macro.templates.filterNot { it.id == template.id }, steps = steps),
            )
            container.invalidateImageCache()
            onSaved(saved)
        }
    }

    fun deleteTarget(macro: Macro, target: Target, onSaved: (Macro) -> Unit) {
        viewModelScope.launch {
            container.files.deleteTemplateImage(macro.id, target.fileName, asTarget = true)
            val steps = macro.steps.map { it.copy(targetIds = it.targetIds - target.id) }
            val saved = container.macroRepository.save(
                macro.copy(targets = macro.targets.filterNot { it.id == target.id }, steps = steps),
            )
            container.invalidateImageCache()
            onSaved(saved)
        }
    }

    fun templateFile(macroId: String, template: Template): File =
        container.files.templateFile(macroId, template.fileName)

    fun targetFile(macroId: String, target: Target): File =
        container.files.targetFile(macroId, target.fileName)

    // ------------------------------------------------------------------
    // 단계 편집
    // ------------------------------------------------------------------

    fun upsertStep(macro: Macro, step: MacroStep, onSaved: (Macro) -> Unit) {
        val steps = if (macro.steps.any { it.id == step.id }) {
            macro.steps.map { if (it.id == step.id) step else it }
        } else {
            macro.steps + step
        }
        saveMacro(macro.copy(steps = steps), onSaved)
    }

    fun removeStep(macro: Macro, step: MacroStep, onSaved: (Macro) -> Unit) {
        saveMacro(macro.copy(steps = macro.steps.filterNot { it.id == step.id }), onSaved)
    }

    fun moveStep(macro: Macro, from: Int, to: Int, onSaved: (Macro) -> Unit) {
        if (from !in macro.steps.indices || to !in macro.steps.indices || from == to) return
        val list = macro.steps.toMutableList()
        list.add(to, list.removeAt(from))
        saveMacro(macro.copy(steps = list), onSaved)
    }

    fun duplicateStep(macro: Macro, step: MacroStep, onSaved: (Macro) -> Unit) {
        val index = macro.steps.indexOfFirst { it.id == step.id }
        if (index < 0) return
        val copy = step.copy(id = java.util.UUID.randomUUID().toString())
        val list = macro.steps.toMutableList().apply { add(index + 1, copy) }
        saveMacro(macro.copy(steps = list), onSaved)
    }

    // ------------------------------------------------------------------
    // 실행
    // ------------------------------------------------------------------

    fun startMacro(macro: Macro) {
        viewModelScope.launch {
            refreshPermissions()
            val perms = _permissions.value
            if (!perms.openCvReady) {
                showMessage("이미지 처리 엔진을 불러오지 못했습니다. 앱을 다시 설치해주세요.")
                return@launch
            }
            if (!perms.accessibilityEnabled) {
                showMessage("접근성 서비스를 먼저 켜주세요.")
                return@launch
            }
            if (!perms.captureReady) {
                showMessage("화면 캡처를 먼저 허용해주세요.")
                return@launch
            }
            container.settingsRepository.update { it.copy(lastMacroId = macro.id) }
            val error = MacroController.startMacro(getApplication(), macro, settings.value)
            if (error != null) showMessage(error)
        }
    }

    fun pauseMacro() = MacroController.pause()
    fun resumeMacro() = MacroController.resume()
    fun stopMacro() = MacroController.stop()
    fun dismissTargetFound() = MacroController.clearTargetFound()

    // ------------------------------------------------------------------
    // 설정
    // ------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            container.files.logsDir.listFiles()?.forEach { it.delete() }
            container.files.screenshotsDir.listFiles()?.forEach { it.delete() }
            showMessage("저장된 화면과 로그를 지웠습니다.")
        }
    }

    fun storageUsageBytes(): Long = container.files.totalStoredBytes()

    fun accessibilitySettingsIntent() = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlaySettingsIntent(context: Context) = android.content.Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    override fun onCleared() {
        clearSnapshot()
        super.onCleared()
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
