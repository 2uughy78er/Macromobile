package com.macromobile.inputmirror.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromobile.inputmirror.MirrorApp
import com.macromobile.inputmirror.input.AutoTestRunner
import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.input.EnvironmentInfo
import com.macromobile.inputmirror.input.GestureDispatcher
import com.macromobile.inputmirror.input.GestureInput
import com.macromobile.inputmirror.input.GestureLog
import com.macromobile.inputmirror.input.GestureRecognizer
import com.macromobile.inputmirror.input.GestureSession
import com.macromobile.inputmirror.input.GestureState
import com.macromobile.inputmirror.input.GestureTracer
import com.macromobile.inputmirror.input.GestureType
import com.macromobile.inputmirror.input.MappedPoint
import com.macromobile.inputmirror.input.MirrorRecord
import com.macromobile.inputmirror.input.MirrorTarget
import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.input.SequenceResult
import com.macromobile.inputmirror.input.TestCase
import com.macromobile.inputmirror.input.TestStats
import com.macromobile.inputmirror.input.TouchPhase
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

/** 지금 진행 중인 제스처를 화면에 보여주기 위한 값. */
data class LiveGesture(
    val id: Long,
    val state: GestureState,
    val distance: Float,
    val thresholdPx: Float,
    val finalType: GestureType? = null,
)

class MirrorViewModel(app: Application) : AndroidViewModel(app), GestureInput {

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

    /** 단계별로 켤 TARGET 개수. 기본은 제한 없음(전체). */
    private var targetLimit: Int = Int.MAX_VALUE

    /** 화면 밀도. dp 임계값을 픽셀로 바꾸는 데 쓴다. 환경 정보를 읽을 때 채워진다. */
    private var density: Float = 1f

    val tracer = GestureTracer()

    // ------------------------------------------------------------------
    // 제스처 판정
    // ------------------------------------------------------------------

    /**
     * TAP / DRAG 판정기.
     *
     * 이 앱에서 제스처 종류를 정하는 곳은 **여기 한 군데뿐**이다. 전송기는 이미 확정된
     * 명령만 받는다. 예전에는 전송기가 들고 있던 직전 스트로크의 유무가 사실상 종류를
     * 정했고, 그래서 이번 터치의 결과가 직전 터치에 좌우됐다.
     */
    private val recognizer = GestureRecognizer()

    override val dragThresholdPx: Float get() = recognizer.dragThresholdPx

    /** 아직 대상에 보내지 않고 들고 있는 구간. 끌기로 판정되면 통째로 함께 보낸다. */
    private val heldPoints = ArrayList<TouchPoint>(64)

    /** 이번 제스처에서 끌기 스트로크를 이미 열었는가. */
    private var dragOpened = false

    private val _liveGesture = MutableStateFlow<LiveGesture?>(null)
    val liveGesture: StateFlow<LiveGesture?> = _liveGesture.asStateFlow()

    /** 요청받은 형식의 제스처 로그. gestureId 로 묶인다. */
    private val _gestureLog = MutableStateFlow<List<String>>(emptyList())
    val gestureLog: StateFlow<List<String>> = _gestureLog.asStateFlow()

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

    private val _sequenceResults = MutableStateFlow<List<SequenceResult>>(emptyList())
    val sequenceResults: StateFlow<List<SequenceResult>> = _sequenceResults.asStateFlow()

    private val _autoProgress = MutableStateFlow<String?>(null)
    val autoProgress: StateFlow<String?> = _autoProgress.asStateFlow()

    private val dispatcher = GestureDispatcher(
        scope = viewModelScope,
        tracer = tracer,
        onRecord = { record ->
            container.logStore.add(record)
            _plannedPoints.value = record.targets
            collectStats(record)
        },
        onNote = { note -> appendGestureLog(note) },
    )

    private val autoRunner = AutoTestRunner(this)

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

    /**
     * 좌표계 진단용 환경 정보를 지금 값으로 새로 읽는다.
     *
     * 컨텍스트를 넘기지 않고 View 만 넘긴다. 예전에는 여기서 Application 컨텍스트를
     * 넘겼고, 그 컨텍스트로 `Context.getDisplay()` 를 부르는 순간
     * `UnsupportedOperationException` 이 터져 테스트 화면이 열리자마자 죽었다.
     * View 의 컨텍스트는 언제나 Activity 라 그런 일이 없다.
     */
    fun captureEnvironment(view: View, readWindowMetrics: Boolean = true) {
        val info = EnvironmentInfo.collect(view, readWindowMetrics)
        _environment.value = info
        if (info.density > 0f && info.density != density) {
            density = info.density
            applySettings(settings.value)
        }
    }

    /**
     * 이번 단계에서 쓸 TARGET 개수 상한.
     *
     * STEP 9 는 TARGET 1개만, STEP 10 은 3개 모두 쓴다. 한 번에 하나씩 늘려야
     * 무엇이 문제를 일으키는지 가려낼 수 있다.
     */
    fun setTargetLimit(limit: Int) {
        if (targetLimit == limit) return
        targetLimit = limit
        applySettings(settings.value)
    }

    /** 마스터 영역(View 공간). 자동 테스트가 쓴다. */
    fun masterRegion(): Region = masterRegion

    private fun applySettings(current: MirrorSettings) {
        dispatcher.dispatchMode = current.dispatchMode
        dispatcher.inputDelayMs = current.inputDelayMs
        container.logStore.writeToFile = current.saveLogToFile
        dispatcher.targets = buildTargets(current)
        recognizer.dragThresholdPx =
            GestureRecognizer.thresholdPx(current.dragThresholdDp, density)
    }

    private fun buildTargets(current: MirrorSettings): List<MirrorTarget> {
        if (!masterRegion.isValid || targetLimit <= 0) return emptyList()
        return targetRegions.take(targetLimit).mapIndexedNotNull { index, region ->
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
        recognizer.reset()
        resetGestureState()
        _targetResults.value = emptyMap()
        _plannedPoints.value = emptyMap()
        _gestureLog.value = emptyList()
        dispatcher.start()
        _mirroring.value = true
        return true
    }

    fun stopMirroring() {
        dispatcher.stop()
        container.logStore.stopSession()
        recognizer.onCancel()
        resetGestureState()
        _mirroring.value = false
    }

    private fun resetGestureState() {
        heldPoints.clear()
        dragOpened = false
        _liveGesture.value = null
    }

    // ------------------------------------------------------------------
    // 마스터 입력 → 판정 → 전송
    //
    // 순서는 언제나 다음과 같다.
    //   MASTER INPUT → 기록 → TAP/DRAG 판정 → 대상 제스처 구성 → dispatchGesture
    // 몇 번째 터치인지는 이 흐름 어디에도 들어오지 않는다.
    // ------------------------------------------------------------------

    fun onMasterDown(point: TouchPoint) {
        if (_mirroring.value) feedDown(point)
    }

    fun onMasterMove(points: List<TouchPoint>) {
        if (_mirroring.value) points.forEach { feedMove(it) }
    }

    fun onMasterUp(point: TouchPoint) {
        if (_mirroring.value) feedUp(point)
    }

    fun onMasterCancel() {
        if (!_mirroring.value) return
        val session = recognizer.onCancel() ?: return
        appendGestureLog(GestureLog.cancel(session))
        dispatcher.submitCancel(session.id)
        resetGestureState()
    }

    // --- GestureInput: 사람 손가락과 자동 테스트가 같은 입구를 쓴다 ------------

    override fun feedDown(point: TouchPoint) {
        // 새 세션이 만들어지고, 이전 제스처의 어떤 상태도 물려받지 않는다.
        val session = recognizer.onDown(point)
        heldPoints.clear()
        heldPoints += point
        dragOpened = false
        appendGestureLog(GestureLog.down(session))
        publishLive(session)
    }

    override fun feedMove(point: TouchPoint) {
        val update = recognizer.onMove(point) ?: return
        heldPoints += point
        appendGestureLog(GestureLog.move(update))
        publishLive(update.session)

        if (settings.value.mode != MirrorMode.STREAMING) return
        val session = update.session

        if (!dragOpened && session.state == GestureState.DRAG) {
            // 임계값을 넘은 순간, **보류해 둔 구간을 통째로 함께** 보낸다.
            // 판정을 기다리느라 늦어지기는 해도 버려지는 점은 없다.
            val heldMs = (point.timestamp - session.down.timestamp).coerceAtLeast(0L)
            appendGestureLog(
                "DRAG_OPEN gestureId=${session.id} 보류했던 점 ${heldPoints.size}개를 " +
                    "함께 보냅니다 (판정까지 ${heldMs}ms, 손실 없음)",
            )
            dispatcher.submitDragOpen(session, ArrayList(heldPoints))
            heldPoints.clear()
            dragOpened = true
        } else if (dragOpened) {
            dispatcher.submitDragContinue(session, ArrayList(heldPoints))
            heldPoints.clear()
        }
    }

    override fun feedUp(point: TouchPoint): GestureType? {
        val outcome = recognizer.onUp(point) ?: return null
        val session = outcome.session
        appendGestureLog(GestureLog.up(outcome))
        _liveGesture.value = LiveGesture(
            id = session.id,
            state = session.state,
            distance = session.maxDistance,
            thresholdPx = session.dragThresholdPx,
            finalType = outcome.type,
        )

        val streaming = settings.value.mode == MirrorMode.STREAMING
        when {
            !streaming -> dispatcher.submitWholeGesture(outcome)

            outcome.type == GestureType.TAP -> {
                // 누르기는 판정이 끝나야 알 수 있으므로 손을 뗄 때 한 번에 보낸다.
                // 그 대신 좌표 정책이 분명하다 — 언제나 DOWN 지점이다.
                appendGestureLog(
                    "TAP_DISPATCH gestureId=${session.id} " +
                        "좌표=DOWN(${"%.1f".format(session.down.x)}, " +
                        "${"%.1f".format(session.down.y)}) 누른시간=${session.durationMs}ms",
                )
                dispatcher.submitTap(session)
            }

            dragOpened -> {
                heldPoints += point
                dispatcher.submitDragEnd(session, ArrayList(heldPoints))
            }

            else -> {
                // MOVE 없이 UP 에서 임계값을 넘은 경우. 열어둔 스트로크가 없으므로
                // 경로 전체를 한 번에 보낸다.
                appendGestureLog(
                    "DRAG_WHOLE gestureId=${session.id} UP 에서 임계값을 넘어 " +
                        "경로 전체를 한 번에 보냅니다.",
                )
                dispatcher.submitWholeGesture(outcome)
            }
        }

        heldPoints.clear()
        dragOpened = false
        return outcome.type
    }

    private fun publishLive(session: GestureSession) {
        _liveGesture.value = LiveGesture(
            id = session.id,
            state = session.state,
            distance = session.maxDistance,
            thresholdPx = session.dragThresholdPx,
        )
    }

    private fun appendGestureLog(text: String) {
        val next = _gestureLog.value + text
        _gestureLog.value = if (next.size > MAX_GESTURE_LOG) {
            next.subList(next.size - MAX_GESTURE_LOG, next.size)
        } else {
            next
        }
        container.logStore.note(text)
    }

    fun gestureLogDump(): String = _gestureLog.value.joinToString("\n")

    // ------------------------------------------------------------------
    // 자동 반복 테스트
    // ------------------------------------------------------------------

    /** 같은 동작을 정해진 횟수만큼 자동으로 흘려보내 성공률을 잰다. */
    fun runAutoTest(case: TestCase, repeatCount: Int) {
        if (!requireRunnable()) return
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
        if (!requireRunnable()) return
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

    /**
     * 순서 섞기 검사 TEST A~E 를 돌린다.
     *
     * 각 제스처가 **순서와 무관하게** 제 움직임대로 판정되는지를 기댓값과 대조한다.
     * 이 검사가 통과해야 "몇 번째 터치인가"로 갈리던 옛 동작이 사라졌다고 말할 수 있다.
     */
    fun runGestureSequences() {
        if (!requireRunnable()) return
        viewModelScope.launch {
            _sequenceResults.value = emptyList()
            TestCase.SEQUENCES.forEach { sequence ->
                val result = autoRunner.runSequence(sequence, masterRegion) { done, total ->
                    _autoProgress.value = "${sequence.name} $done/$total"
                }
                _sequenceResults.value = _sequenceResults.value + result
            }
            _autoProgress.value = null
        }
    }

    private fun requireRunnable(): Boolean {
        if (!_mirroring.value) {
            showMessage("먼저 미러링을 시작해주세요.")
            return false
        }
        if (!masterRegion.isValid) {
            showMessage("테스트 영역이 아직 준비되지 않았습니다.")
            return false
        }
        return true
    }

    fun clearStats() {
        _stats.value = emptyList()
        _sequenceResults.value = emptyList()
    }

    /** UP 단계만 한 번의 동작으로 세어 성공률을 계산한다. */
    private fun collectStats(record: MirrorRecord) {
        val bucket = statsBucket ?: return
        if (record.phase != TouchPhase.UP) return
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
        _gestureLog.value = emptyList()
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

    fun formatRecord(record: MirrorRecord): String = container.logStore.format(record)

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    override fun onCleared() {
        dispatcher.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_GESTURE_LOG = 400
    }
}
