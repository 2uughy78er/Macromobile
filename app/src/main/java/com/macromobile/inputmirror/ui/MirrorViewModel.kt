package com.macromobile.inputmirror.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromobile.inputmirror.MirrorApp
import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.input.GestureDispatcher
import com.macromobile.inputmirror.input.MirrorTarget
import com.macromobile.inputmirror.input.TouchPoint
import com.macromobile.inputmirror.model.MirrorMode
import com.macromobile.inputmirror.model.MirrorSettings
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.service.MirrorAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 기기가 실제로 무엇을 지원하는지. 검증 결과를 그대로 보여준다. */
data class DeviceCapability(
    val accessibilityConnected: Boolean = false,
    val maxStrokeCount: Int = 0,
    val maxGestureDurationMs: Long = 0L,
)

class MirrorViewModel(app: Application) : AndroidViewModel(app) {

    private val container = MirrorApp.container()

    val settings: StateFlow<MirrorSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, MirrorSettings())

    val records = container.logStore.records

    private val _capability = MutableStateFlow(DeviceCapability())
    val capability: StateFlow<DeviceCapability> = _capability.asStateFlow()

    private val _mirroring = MutableStateFlow(false)
    val mirroring: StateFlow<Boolean> = _mirroring.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var masterRegion = Region.EMPTY
    private var targetRegions: List<Region> = emptyList()

    private val dispatcher = GestureDispatcher(viewModelScope) { record ->
        container.logStore.add(record)
    }

    init {
        viewModelScope.launch {
            MirrorAccessibilityService.connected.collect { refreshCapability() }
        }
        viewModelScope.launch {
            settings.collect { applySettings(it) }
        }
    }

    fun refreshCapability() {
        _capability.value = DeviceCapability(
            accessibilityConnected = MirrorAccessibilityService.isConnected,
            maxStrokeCount = runCatching { MirrorAccessibilityService.maxStrokeCount }
                .getOrDefault(0),
            maxGestureDurationMs = runCatching { MirrorAccessibilityService.maxGestureDurationMs }
                .getOrDefault(0L),
        )
    }

    fun showMessage(text: String?) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------
    // 테스트 영역
    // ------------------------------------------------------------------

    /** 테스트 뷰가 영역을 정하면 알려준다. 화면이 회전해도 다시 불린다. */
    fun onAreasChanged(master: Region, targets: List<Region>) {
        masterRegion = master
        targetRegions = targets
        applySettings(settings.value)
    }

    private fun applySettings(current: MirrorSettings) {
        dispatcher.mode = current.mode
        dispatcher.inputDelayMs = current.inputDelayMs
        container.logStore.writeToFile = current.saveLogToFile
        dispatcher.targets = buildTargets(current)
    }

    private fun buildTargets(current: MirrorSettings): List<MirrorTarget> {
        if (!masterRegion.isValid) return emptyList()
        return targetRegions.mapIndexedNotNull { index, region ->
            val enabled = current.enabledTargets.getOrElse(index) { true }
            if (!enabled || !region.isValid) return@mapIndexedNotNull null
            MirrorTarget(
                id = "target_$index",
                name = "TARGET ${index + 1}",
                transformer = CoordinateTransformer(
                    master = masterRegion,
                    target = region,
                    fitMode = current.fitMode,
                    scaleX = current.scaleX,
                    scaleY = current.scaleY,
                    offsetX = current.offsetX,
                    offsetY = current.offsetY,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 미러링 시작 / 중지
    // ------------------------------------------------------------------

    fun startMirroring(): Boolean {
        refreshCapability()
        if (!_capability.value.accessibilityConnected) {
            showMessage("접근성 서비스를 먼저 켜주세요. 이게 없으면 대상에 입력을 넣을 수 없습니다.")
            return false
        }
        if (dispatcher.targets.isEmpty()) {
            showMessage("사용할 대상이 없습니다. 설정에서 TARGET 을 하나 이상 켜주세요.")
            return false
        }
        container.logStore.startSession()
        dispatcher.start()
        _mirroring.value = true
        return true
    }

    fun stopMirroring() {
        dispatcher.stop()
        container.logStore.stopSession()
        _mirroring.value = false
    }

    // ------------------------------------------------------------------
    // 마스터 입력 전달
    // ------------------------------------------------------------------

    fun onMasterDown(point: TouchPoint) {
        if (_mirroring.value) dispatcher.onDown(point)
    }

    fun onMasterMove(points: List<TouchPoint>) {
        if (_mirroring.value) dispatcher.onMove(points)
    }

    fun onMasterUp(points: List<TouchPoint>, wholePath: List<TouchPoint>) {
        if (!_mirroring.value) return
        if (settings.value.mode == MirrorMode.BATCH) {
            dispatcher.onWholeStroke(wholePath)
        } else {
            dispatcher.onUp(points)
        }
    }

    fun onMasterCancel() {
        if (_mirroring.value) dispatcher.onCancel()
    }

    // ------------------------------------------------------------------
    // 설정 / 로그
    // ------------------------------------------------------------------

    fun updateSettings(transform: (MirrorSettings) -> MirrorSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    fun clearLogs() {
        container.logStore.clear()
        showMessage("화면 기록을 지웠습니다.")
    }

    fun deleteLogFiles() {
        container.logStore.deleteAllLogs()
        container.logStore.clear()
        showMessage("저장된 로그 파일을 모두 지웠습니다.")
    }

    fun logSummary(): String {
        val files = container.logStore.logFiles()
        if (files.isEmpty()) return "저장된 로그 파일이 없습니다."
        return "로그 파일 ${files.size}개 · ${container.logStore.totalBytes() / 1024}KB"
    }

    fun formatRecord(record: com.macromobile.inputmirror.input.MirrorRecord): String =
        container.logStore.format(record)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    override fun onCleared() {
        dispatcher.stop()
        super.onCleared()
    }
}
