package com.macromobile.inputmirror.mirror

import android.util.Log
import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.input.GestureDispatcher
import com.macromobile.inputmirror.input.GestureLog
import com.macromobile.inputmirror.input.GestureRecognizer
import com.macromobile.inputmirror.input.GestureTracer
import com.macromobile.inputmirror.input.MirrorRecord
import com.macromobile.inputmirror.input.MirrorTarget
import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.input.TouchPoint
import com.macromobile.inputmirror.input.TraceResult
import com.macromobile.inputmirror.input.TraceStage
import com.macromobile.inputmirror.model.LayoutStatus
import com.macromobile.inputmirror.model.MirrorLayout
import com.macromobile.inputmirror.model.MirrorSettings
import com.macromobile.inputmirror.overlay.FloatingControlView
import com.macromobile.inputmirror.overlay.MasterInputView
import com.macromobile.inputmirror.service.MirrorAccessibilityService
import com.macromobile.inputmirror.storage.MirrorLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 본 모드의 미러링 엔진.
 *
 * ## 왜 서비스 안에 있는가
 *
 * 사용자가 게임을 하는 동안 우리 Activity 는 화면에 없다. 미러링이 Activity 에 매여
 * 있으면 그때 멈춘다. 그래서 엔진은 접근성 서비스가 들고 있고, 화면은 [MirrorRuntime]
 * 을 들여다보기만 한다.
 *
 * ## 왜 제스처가 끝난 뒤에 보내는가
 *
 * `dispatchGesture` 는 문서상 **진행 중인 제스처를 사용자 것이든 무엇이든 취소한다.**
 * 손가락이 아직 화면에 있는 동안 주입하면 그 손가락이 끊긴다. 그래서 본 모드는
 * 손을 뗀 뒤 한 번에 재생한다. 지연은 제스처 한 번 분량이고, 그 값을 로그에 적는다.
 * 실시간으로 보내는 척하지 않는다.
 *
 * ## 판정은 그대로 가져다 쓴다
 *
 * TAP/DRAG 판정은 검증 MVP 에서 쓰던 [GestureRecognizer] 를 **그대로** 쓴다.
 * 좌표 변환도 [CoordinateTransformer] 를 그대로 쓴다. 이 클래스는 그 둘을 실제 화면
 * 영역에 연결하고 상태를 관리할 뿐, 판정이나 변환을 새로 만들지 않는다.
 */
class MirrorEngine(
    private val service: MirrorAccessibilityService,
    private val scope: CoroutineScope,
    private val logStore: MirrorLogStore,
) {
    private val tracer = GestureTracer()
    private val recognizer = GestureRecognizer()

    private val dispatcher = GestureDispatcher(
        scope = scope,
        tracer = tracer,
        onRecord = { record -> onDispatchRecord(record) },
        onNote = { note -> MirrorRuntime.addLog(note) },
    ).apply {
        // 본 모드의 좌표는 이미 화면 좌표다. 다시 옮길 것이 없다.
        geometry = ScreenGeometry.IDENTITY

        // 주입이 우리 오버레이로 되돌아오지 않도록 잠시 터치를 받지 않게 한다.
        beforeDispatch = { setMasterOverlayTouchable(false) }
        afterDispatch = { setMasterOverlayTouchable(true) }
    }

    private var layout: MirrorLayout = MirrorLayout()
    private var settings: MirrorSettings = MirrorSettings()
    private var masterView: MasterInputView? = null
    private var floatingView: FloatingControlView? = null

    /** 떠 있는 조작 버튼의 위치. 사용자가 옮긴 자리를 기억한다. */
    private var floatingX = 0
    private var floatingY = 0

    /** 이번 제스처에서 각 대상에 주입하려던 경로의 시작·끝. 로그에 쓴다. */
    private var plannedPaths: Map<String, String> = emptyMap()

    val state: MirrorState get() = MirrorRuntime.state.value

    // ------------------------------------------------------------------
    // 시작 / 일시정지 / 정지
    // ------------------------------------------------------------------

    /**
     * 점검을 거쳐 미러링을 시작한다.
     *
     * 점검에 걸리면 **시작하지 않고 이유를 남긴다.** 반쯤 켜진 상태로 두지 않는다.
     */
    fun start(layout: MirrorLayout, settings: MirrorSettings): Boolean {
        this.layout = layout
        this.settings = settings
        MirrorRuntime.setState(MirrorState.PREPARING)
        MirrorRuntime.setBlockedReason(null)

        val failure = preflight(layout)
        if (failure != null) {
            MirrorRuntime.addFailure(failure)
            MirrorRuntime.setBlockedReason(failure.describe())
            MirrorRuntime.setState(MirrorState.IDLE)
            return false
        }

        applySettings(settings)
        if (!showMasterOverlay()) {
            val overlayFailure = MirrorFailure(
                MirrorError.SERVICE_ERROR,
                "MASTER 입력 오버레이를 띄우지 못했습니다.",
            )
            MirrorRuntime.addFailure(overlayFailure)
            MirrorRuntime.setBlockedReason(overlayFailure.describe())
            MirrorRuntime.setState(MirrorState.IDLE)
            return false
        }

        logStore.startSession()
        tracer.reset()
        recognizer.reset()
        dispatcher.start()
        showFloatingControl()
        MirrorRuntime.setState(MirrorState.RUNNING)
        floatingView?.state = MirrorState.RUNNING
        MirrorRuntime.addLog("START — 대상 ${dispatcher.targets.size}개")
        return true
    }

    /** 새 입력 전달을 멈춘다. 영역과 오버레이는 그대로 둔다. */
    fun pause() {
        if (state != MirrorState.RUNNING) return
        // 진행 중인 제스처는 확정하지 않고 버린다. 절반만 보내면 대상이 이상해진다.
        recognizer.onCancel()?.let { dispatcher.submitCancel(it.id) }
        MirrorRuntime.setState(MirrorState.PAUSED)
        floatingView?.state = MirrorState.PAUSED
        masterView?.statusText = "일시정지"
        masterView?.active = false
        MirrorRuntime.addLog("PAUSE")
    }

    fun resume() {
        if (state != MirrorState.PAUSED) return
        MirrorRuntime.setState(MirrorState.RUNNING)
        floatingView?.state = MirrorState.RUNNING
        masterView?.active = true
        masterView?.statusText = null
        MirrorRuntime.addLog("RESUME")
    }

    fun stop() {
        recognizer.onCancel()
        dispatcher.stop()
        logStore.stopSession()
        hideMasterOverlay()
        hideFloatingControl()
        MirrorRuntime.setState(MirrorState.STOPPED)
        MirrorRuntime.addLog("STOP")
    }

    /** 화면 구성이 바뀌었다. 조용히 계속하지 않고 멈추고 알린다. */
    fun onScreenChanged() {
        if (state != MirrorState.RUNNING && state != MirrorState.PAUSED) return
        val failure = MirrorFailure(
            MirrorError.WINDOW_CHANGED,
            "화면 구성이 변경되었습니다. MASTER/TARGET 영역을 다시 확인해주세요.",
        )
        MirrorRuntime.addFailure(failure)
        MirrorRuntime.setBlockedReason(failure.describe())
        stop()
    }

    // ------------------------------------------------------------------
    // 점검
    // ------------------------------------------------------------------

    private fun preflight(layout: MirrorLayout): MirrorFailure? {
        if (!MirrorAccessibilityService.isConnected) {
            return MirrorFailure(MirrorError.ACCESSIBILITY_NOT_CONNECTED)
        }
        val now = service.screenFingerprint()
        return when (val status = layout.status(now)) {
            LayoutStatus.Ready -> null
            LayoutStatus.NoMaster -> MirrorFailure(MirrorError.MASTER_NOT_FOUND, status.reason)
            LayoutStatus.NoTarget -> MirrorFailure(MirrorError.TARGET_NOT_AVAILABLE, status.reason)
            is LayoutStatus.ScreenChanged -> MirrorFailure(MirrorError.WINDOW_CHANGED, status.reason)
            is LayoutStatus.MasterTooSmall ->
                MirrorFailure(MirrorError.INVALID_COORDINATE, status.reason)
            is LayoutStatus.TargetTooSmall ->
                MirrorFailure(MirrorError.INVALID_COORDINATE, status.reason, status.region.name)
            is LayoutStatus.Overlap -> MirrorFailure(MirrorError.INVALID_COORDINATE, status.reason)
        }
    }

    // ------------------------------------------------------------------
    // 설정 → 대상 목록
    // ------------------------------------------------------------------

    fun applySettings(settings: MirrorSettings) {
        this.settings = settings
        val density = service.resources.displayMetrics.density
        recognizer.dragThresholdPx =
            GestureRecognizer.thresholdPx(settings.dragThresholdDp, density)
        dispatcher.dispatchMode = settings.dispatchMode
        dispatcher.inputDelayMs = settings.inputDelayMs
        logStore.writeToFile = settings.saveLogToFile
        dispatcher.targets = buildTargets()
    }

    /**
     * 주입 대상 목록.
     *
     * **MASTER 도 목록에 들어간다.** 사용자는 게임이 아니라 오버레이를 만지므로, MASTER
     * 게임 역시 주입으로만 입력을 받기 때문이다. MASTER 의 변환은 자기 자신으로 가는
     * 변환이라 좌표가 그대로 유지된다.
     */
    private fun buildTargets(): List<MirrorTarget> {
        val master = layout.master ?: return emptyList()
        val all = listOf(master) + layout.deliverableTargets
        return all.map { region ->
            MirrorTarget(
                id = region.id,
                name = region.name,
                delayMs = region.delayMs,
                transformer = CoordinateTransformer(
                    master = master.bounds,
                    target = region.bounds,
                    fitMode = settings.fitMode,
                    scaleX = settings.scaleX,
                    scaleY = settings.scaleY,
                    offsetX = settings.offsetX,
                    offsetY = settings.offsetY,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 오버레이
    // ------------------------------------------------------------------

    private fun showMasterOverlay(): Boolean {
        val controller = service.overlay ?: return false
        val master = layout.master ?: return false
        val view = MasterInputView(
            context = service,
            onDown = ::handleDown,
            onMove = ::handleMove,
            onUp = ::handleUp,
            onCancel = ::handleCancel,
        )
        masterView = view
        return controller.show(
            KEY_MASTER_INPUT,
            view,
            controller.regionParams(master.bounds, touchable = true),
        )
    }

    private fun hideMasterOverlay() {
        service.overlay?.remove(KEY_MASTER_INPUT)
        masterView = null
    }

    private fun setMasterOverlayTouchable(touchable: Boolean) {
        service.overlay?.setTouchable(KEY_MASTER_INPUT, touchable)
    }

    /**
     * 떠 있는 조작 버튼.
     *
     * 사용자가 게임 안에 있을 때도 즉시 멈출 수 있어야 하므로, 우리 화면이 아니라
     * 오버레이로 띄운다. 화면을 덮지 않는 작은 버튼 하나다.
     */
    private fun showFloatingControl() {
        val controller = service.overlay ?: return
        if (floatingX == 0 && floatingY == 0) {
            val screen = controller.screenFingerprint()
            floatingX = (screen.width * 0.55f).toInt()
            floatingY = (screen.height * 0.02f).toInt()
        }
        val view = FloatingControlView(
            context = service,
            onPauseOrResume = {
                if (MirrorRuntime.state.value == MirrorState.PAUSED) resume() else pause()
            },
            onStop = { stop() },
            onMoved = { dx, dy ->
                floatingX += dx
                floatingY += dy
                service.overlay?.update(KEY_FLOATING_CONTROL) { params ->
                    params.x = floatingX
                    params.y = floatingY
                }
            },
        )
        floatingView = view
        controller.show(
            KEY_FLOATING_CONTROL,
            view,
            controller.floatingParams(floatingX, floatingY),
        )
    }

    private fun hideFloatingControl() {
        service.overlay?.remove(KEY_FLOATING_CONTROL)
        floatingView = null
    }

    // ------------------------------------------------------------------
    // 입력 → 판정 → 주입
    // ------------------------------------------------------------------

    private fun handleDown(point: TouchPoint) {
        if (!state.deliversInput) return
        val session = recognizer.onDown(point)
        MirrorRuntime.addLog(GestureLog.down(session))
        logStore.note(GestureLog.down(session))
        masterView?.clearTrail()
        masterView?.statusText = "#${session.id} PENDING"
    }

    private fun handleMove(points: List<TouchPoint>) {
        if (!state.deliversInput) return
        points.forEach { point ->
            val update = recognizer.onMove(point) ?: return@forEach
            val line = GestureLog.move(update)
            MirrorRuntime.addLog(line)
            logStore.note(line)
            masterView?.statusText =
                "#${update.session.id} ${update.stateAfter} " +
                    "${"%.0f".format(update.distance)}/${"%.0f".format(recognizer.dragThresholdPx)}px"
        }
    }

    private fun handleUp(point: TouchPoint) {
        if (!state.deliversInput) return
        val outcome = recognizer.onUp(point) ?: return
        val session = outcome.session
        val line = GestureLog.up(outcome)
        MirrorRuntime.addLog(line)
        logStore.note(line)
        masterView?.statusText = "#${session.id} ${outcome.type.label}"

        if (dispatcher.targets.isEmpty()) {
            MirrorRuntime.addFailure(MirrorFailure(MirrorError.TARGET_NOT_AVAILABLE))
            return
        }

        // 대상별로 주입하려는 경로의 시작·끝을 미리 적어 둔다. 로그가 좌표를 함께 보여준다.
        plannedPaths = dispatcher.targets.associate { target ->
            val from = target.transformer.mapClamped(session.down.x, session.down.y)
            val to = target.transformer.mapClamped(point.x, point.y)
            target.name to if (from == null || to == null) {
                "(변환 불가)"
            } else {
                "(${from.x.toInt()},${from.y.toInt()}) → (${to.x.toInt()},${to.y.toInt()})"
            }
        }

        MirrorRuntime.addGesture(
            GestureSummary(
                gestureId = session.id,
                type = outcome.type,
                masterFrom = session.down.x to session.down.y,
                masterTo = point.x to point.y,
                distance = session.maxDistance,
                durationMs = session.durationMs,
                targetPaths = plannedPaths,
            ),
        )

        // 손을 뗀 뒤 한 번에 보낸다. 이유는 클래스 설명 참조.
        dispatcher.submitWholeGesture(outcome)
    }

    private fun handleCancel() {
        val session = recognizer.onCancel() ?: return
        MirrorRuntime.addLog(GestureLog.cancel(session))
        dispatcher.submitCancel(session.id)
    }

    // ------------------------------------------------------------------
    // 결과 기록 — 대상마다 따로
    // ------------------------------------------------------------------

    private fun onDispatchRecord(record: MirrorRecord) {
        logStore.add(record)
        scope.launch { recordTargetResults(record) }
    }

    private fun recordTargetResults(record: MirrorRecord) {
        val callbacks = tracer.snapshot()
            .filter { it.stage == TraceStage.CALLBACK && it.gestureId == record.gestureId }
        callbacks.forEach { entry ->
            entry.targetNames.forEach { name ->
                MirrorRuntime.setTargetResult(name, entry.result.name)
                if (entry.result != TraceResult.COMPLETED) {
                    MirrorRuntime.addFailure(
                        MirrorFailure(
                            error = when (entry.result) {
                                TraceResult.CANCELLED -> MirrorError.GESTURE_CANCELLED
                                TraceResult.REJECTED -> MirrorError.GESTURE_REJECTED
                                TraceResult.EXCEPTION -> MirrorError.SERVICE_ERROR
                                else -> MirrorError.UNKNOWN_ERROR
                            },
                            detail = entry.detail,
                            targetName = name,
                        ),
                    )
                }
            }
        }
        if (!record.success) {
            Log.w(TAG, "주입 실패: ${record.error}")
        }
    }

    fun traceDump(): String = tracer.dump()

    companion object {
        private const val TAG = "MirrorEngine"
        const val KEY_MASTER_INPUT = "master_input"
        const val KEY_FLOATING_CONTROL = "floating_control"
    }
}
