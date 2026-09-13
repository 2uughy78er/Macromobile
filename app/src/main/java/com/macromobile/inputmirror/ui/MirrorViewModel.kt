package com.macromobile.inputmirror.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromobile.inputmirror.MirrorApp
import android.view.View
import com.macromobile.inputmirror.input.AutoTestRunner
import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.input.EnvironmentInfo
import com.macromobile.inputmirror.input.GestureDispatcher
import com.macromobile.inputmirror.input.GestureTracer
import com.macromobile.inputmirror.input.MappedPoint
import com.macromobile.inputmirror.input.MirrorTarget
import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.input.TestCase
import com.macromobile.inputmirror.input.TestStats
import com.macromobile.inputmirror.input.TouchPoint
import com.macromobile.inputmirror.input.TraceResult
import com.macromobile.inputmirror.input.TraceStage
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

    val tracer = GestureTracer()

    /** 각 대상에 주입하려는 화면 좌표. 테스트 화면이 마커로 표시한다. */
    private val _plannedPoints = MutableStateFlow<Map<String, MappedPoint>>(emptyMap())
    val plannedPoints: StateFlow<Map<String, MappedPoint>> = _plannedPoints.asStateFlow()

    /** 대상별 마지막 결과. COMPLETED / CANCELLED / REJECTED / EXCEPTION. */
    private val _targetResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val targetResults: StateFlow<Map<String, String>> = _targetResults.asStateFlow()

    private val _environment = MutableStateFlow<EnvironmentInfo?>(null)
    val environment: StateFlow<EnvironmentInfo?> = _environment.asStateFlow()

    private val _stats = MutableStateFlow<List<TestStats>>(emptyList())
    val stats: StateFlow<List<TestStats>> = _stats.asStateFlow()

    private val _autoProgress = MutableStateFlow<String?>(null)
    val autoProgress: StateFlow<String?> = _autoProgress.asStateFlow()

    private val dispatcher = GestureDispatcher(viewModelScope, tracer) { record ->
        container.logStore.add(record)
        _plannedPoints.value = record.targets
        collectStats(record)
    }

    private val autoRunner = AutoTestRunner(dispatcher)

    /** 이번 통계 수집 구간의 이름. 자동 테스트가 돌 때만 채워진다. */
    private var statsBucket: String? = null

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

    /**
     * 테스트 뷰가 영역과 화면상 위치를 알려준다.
     *
     * 영역은 View 공간이고 [geometry] 가 그것을 화면 공간으로 옮긴다. 회전하거나 인셋이
     * 바뀌면 다시 불리므로, 그때마다 변환기를 새로 만든다.
     */
    fun onAreasChanged(master: Region, targets: List<Region>, geometry: ScreenGeometry) {
        masterRegion = master
        targetRegions = targets
        dispatcher.geometry = geometry
        applySettings(settings.value)
    }

    /** 좌표계 진단용 환경 정보를 지금 값으로 새로 읽는다. */
    fun captureEnvironment(view: View?) {
        _environment.value = EnvironmentInfo.collect(getApplication(), view)
    }

    /** 마스터 영역(View 공간). 자동 테스트가 쓴다. */
    fun masterRegion(): Region = masterRegion

    private fun applySettings(current: MirrorSettings) {
        dispatcher.mode = current.mode
        dispatcher.dispatchMode = current.dispatchMode
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
        tracer.reset()
        _targetResults.value = emptyMap()
        _plannedPoints.value = emptyMap()
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
        updateTargetResults()
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

    // ------------------------------------------------------------------
    // 자동 반복 테스트
    // ------------------------------------------------------------------

    /** 같은 동작을 정해진 횟수만큼 자동으로 흘려보내 성공률을 잰다. */
    fun runAutoTest(case: TestCase, repeatCount: Int) {
        if (!_mirroring.value) {
            showMessage("먼저 미러링을 시작해주세요.")
            return
        }
        if (!masterRegion.isValid) {
            showMessage("테스트 영역이 아직 준비되지 않았습니다.")
            return
        }
        viewModelScope.launch {
            statsBucket = case.name
            _stats.value = _stats.value.filterNot { it.name == case.name } + TestStats(case.name)
            autoRunner.run(case, masterRegion, repeatCount) { done, total ->
                _autoProgress.value = "${case.name} $done/$total"
            }
            _autoProgress.value = null
            statsBucket = null
        }
    }

    /** 표준 테스트 9종을 차례로 돌린다(요구사항 10단계). */
    fun runStandardSuite(repeatEach: Int) {
        if (!_mirroring.value) {
            showMessage("먼저 미러링을 시작해주세요.")
            return
        }
        viewModelScope.launch {
            TestCase.STANDARD.forEach { case ->
                statsBucket = case.name
                _stats.value = _stats.value.filterNot { it.name == case.name } + TestStats(case.name)
                autoRunner.run(case, masterRegion, repeatEach) { done, total ->
                    _autoProgress.value = "${case.name} $done/$total"
                }
            }
            _autoProgress.value = null
            statsBucket = null
        }
    }

    fun clearStats() {
        _stats.value = emptyList()
    }

    /** UP 단계만 한 번의 동작으로 세어 성공률을 계산한다. */
    private fun collectStats(record: com.macromobile.inputmirror.input.MirrorRecord) {
        val bucket = statsBucket ?: return
        if (record.phase != com.macromobile.inputmirror.input.TouchPhase.UP) return
        _stats.value = _stats.value.map { stat ->
            if (stat.name != bucket) return@map stat
            val lastResult = tracer.snapshot().lastOrNull { it.stage == TraceStage.CALLBACK }?.result
            stat.copy(
                attempts = stat.attempts + 1,
                success = stat.success + if (record.success) 1 else 0,
                cancelled = stat.cancelled + if (lastResult == TraceResult.CANCELLED) 1 else 0,
                rejected = stat.rejected + if (lastResult == TraceResult.REJECTED) 1 else 0,
                exception = stat.exception + if (lastResult == TraceResult.EXCEPTION) 1 else 0,
                latenciesMs = stat.latenciesMs + record.latencyMs,
            )
        }
        updateTargetResults()
    }

    /** 추적 기록에서 대상별 마지막 결과를 뽑아 화면에 표시한다. */
    private fun updateTargetResults() {
        val results = LinkedHashMap<String, String>()
        tracer.snapshot().filter { it.stage == TraceStage.CALLBACK }.forEach { entry ->
            entry.targetNames.forEach { name -> results[name] = entry.result.name }
        }
        _targetResults.value = results
    }

    /** 추적 기록 전체. 문제 원인을 눈으로 확인하는 데 쓴다. */
    fun traceDump(): String = tracer.dump()

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
