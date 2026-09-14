package com.macromobile.inputmirror.input

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.macromobile.inputmirror.model.DispatchMode
import com.macromobile.inputmirror.service.DispatchResult
import com.macromobile.inputmirror.service.MirrorAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 대상 하나.
 *
 * [transformer] 는 **View 공간의 마스터 좌표 → View 공간의 대상 좌표** 를 담당한다.
 * 화면 좌표로 옮기는 일은 [GestureDispatcher] 가 [ScreenGeometry] 로 따로 한다.
 * 두 가지를 한 곳에서 섞으면 어느 공간의 좌표인지 알 수 없게 된다.
 */
data class MirrorTarget(
    val id: String,
    val name: String,
    val transformer: CoordinateTransformer,
    /**
     * 이 대상에만 주는 지연(ms).
     *
     * 한 제스처에 묶어 보낼 때는 스트로크의 `startTime` 으로 들어간다. 대상마다 조금씩
     * 늦춰 보내고 싶을 때 쓴다. 0 이라 해서 하드웨어 수준의 완전한 동시 입력이 되는 것은
     * 아니다 — 시스템이 제스처를 풀어내는 순서와 시간은 우리가 정하지 못한다.
     */
    val delayMs: Long = 0L,
)

/**
 * 전송 명령.
 *
 * **모든 명령이 gestureId 를 들고 다닌다.** 전송기는 자기가 들고 있는 스트로크가 어느
 * 제스처의 것인지 확인할 수 있고, 번호가 다르면 물려받지 않고 버린다. 이전 제스처의
 * 상태가 다음 제스처로 새는 길을 구조적으로 막는다.
 */
private sealed interface Command {
    val gestureId: Long

    /** 완결된 누르기 하나. 이어붙일 것이 없다. */
    data class Tap(
        override val gestureId: Long,
        val point: TouchPoint,
        val durationMs: Long,
    ) : Command

    /** 끌기의 시작. DOWN 부터 임계값을 넘은 지점까지의 경로 **전체**를 담는다. */
    data class DragOpen(override val gestureId: Long, val points: List<TouchPoint>) : Command

    /** 끌기의 중간 구간. */
    data class DragContinue(override val gestureId: Long, val points: List<TouchPoint>) : Command

    /** 끌기의 마지막 구간. 여기서 제스처가 닫힌다. */
    data class DragEnd(override val gestureId: Long, val points: List<TouchPoint>) : Command

    /** 다 끝난 제스처를 한 번에. 완료 후 전송 방식에서 쓴다. */
    data class Whole(
        override val gestureId: Long,
        val points: List<TouchPoint>,
        val type: GestureType,
    ) : Command

    data class Cancel(override val gestureId: Long) : Command
}

/** 대상 하나에 대해 진행 중인 스트로크와 그 끝점. */
private class ActiveStroke(
    val stroke: GestureDescription.StrokeDescription,
    /** 이 스트로크가 끝난 지점(화면 좌표). 이어붙일 경로는 **반드시 여기서 시작**해야 한다. */
    val endPoint: MappedPoint,
)

/** 진행 중인 끌기 한 건. 어느 제스처의 것인지 번호로 못박아 둔다. */
private class ActiveChain(
    val gestureId: Long,
    val strokes: List<ActiveStroke>,
)

/**
 * 판정이 끝난 제스처를 대상들에 주입한다.
 *
 * ## 이 클래스가 하지 않는 일
 *
 * **TAP 인지 DRAG 인지 판단하지 않는다.** 그 판단은 [GestureRecognizer] 가 이동거리만
 * 보고 내리고, 여기로는 이미 확정된 명령만 들어온다. 예전에는 이 클래스가 들고 있던
 * "직전 스트로크"의 존재 여부가 사실상 TAP/DRAG 를 결정했고, 그래서 **직전 제스처가
 * 어떻게 끝났느냐에 따라 이번 터치의 종류가 달라졌다.** 그 구조를 없앤 것이 이 판이다.
 *
 * ## 좌표
 * ```
 * 마스터 View 좌표 → (CoordinateTransformer) → 대상 View 좌표
 *                 → (ScreenGeometry)        → 대상 Screen 좌표 → dispatchGesture
 * ```
 */
class GestureDispatcher(
    private val scope: CoroutineScope,
    private val tracer: GestureTracer,
    private val onRecord: (MirrorRecord) -> Unit,
    /** 전송기 내부에서 일어난 일을 사람이 읽을 수 있게 알린다. 숨기지 않기 위한 통로. */
    private val onNote: (String) -> Unit = {},
) {
    private var commands: Channel<Command>? = null

    /** 진행 중인 끌기. 제스처가 끝나거나 다른 제스처가 오면 즉시 버린다. */
    private var chain: ActiveChain? = null

    @Volatile
    var targets: List<MirrorTarget> = emptyList()

    @Volatile
    var dispatchMode: DispatchMode = DispatchMode.COMBINED

    @Volatile
    var inputDelayMs: Long = 0L

    /** View 좌표를 화면 좌표로 옮기는 변환. 테스트 뷰가 자리를 잡으면 채워진다. */
    @Volatile
    var geometry: ScreenGeometry = ScreenGeometry.IDENTITY

    /**
     * 주입 직전/직후에 부를 일.
     *
     * 본 모드에서는 MASTER 입력 오버레이를 잠시 터치 불가로 바꿔야 한다. 그러지 않으면
     * 주입이 게임이 아니라 우리 오버레이로 되돌아온다. 그 처리를 전송기가 직접 알 필요는
     * 없으므로 바깥에서 끼워 넣는다.
     */
    @Volatile
    var beforeDispatch: (suspend () -> Unit)? = null

    @Volatile
    var afterDispatch: (suspend () -> Unit)? = null

    /** 지금 MASTER 오버레이가 터치를 받는 상태인지. 로그에 그대로 남긴다. */
    @Volatile
    var overlayTouchable: (() -> Boolean?)? = null

    /**
     * 화면 경계. 주입 좌표가 이 밖으로 나가면 보내지 않는다.
     *
     * 화면이 회전하거나 창 크기가 바뀐 뒤 옛 좌표가 그대로 쓰이면 엉뚱한 곳이 눌린다.
     * 조용히 자르지 않고 거부하고 이유를 남긴다.
     */
    @Volatile
    var displayBounds: com.macromobile.inputmirror.model.Region? = null

    fun start() {
        stop()
        val channel = Channel<Command>(Channel.UNLIMITED)
        commands = channel
        scope.launch {
            for (command in channel) {
                // 명령이 어떻게 끝나든 — 성공이든 예외든 — 오버레이를 터치 가능으로
                // 되돌린다. 이걸 빠뜨리면 주입 한 번 실패한 뒤로 MASTER 입력이
                // 영영 우리에게 오지 않는다.
                runCatching { handle(command) }.also { afterDispatch?.invoke() }
                    .onFailure { error ->
                    // 여기서 삼키면 실패가 화면에 보이지 않는다. 반드시 기록으로 남긴다.
                    Log.e(TAG, "명령 처리 실패", error)
                    chain = null
                    trace(
                        TraceEntry(
                            gestureId = command.gestureId,
                            stage = TraceStage.ERROR,
                            elapsedNanos = GestureTracer.now(),
                            phase = TouchPhase.CANCEL,
                            targetNames = targets.map { it.name },
                            result = TraceResult.EXCEPTION,
                            detail = "${error::class.java.simpleName}: ${error.message}",
                        ),
                    )
                }
            }
        }
    }

    fun stop() {
        commands?.close()
        commands = null
        chain = null
    }

    // ------------------------------------------------------------------
    // 판정이 끝난 제스처만 받는다
    // ------------------------------------------------------------------

    fun submitTap(session: GestureSession) {
        val point = session.tapPoint()
        send(Command.Tap(session.id, point, session.durationMs))
    }

    fun submitDragOpen(session: GestureSession, points: List<TouchPoint>) =
        send(Command.DragOpen(session.id, points))

    fun submitDragContinue(session: GestureSession, points: List<TouchPoint>) {
        if (points.isNotEmpty()) send(Command.DragContinue(session.id, points))
    }

    fun submitDragEnd(session: GestureSession, points: List<TouchPoint>) =
        send(Command.DragEnd(session.id, points))

    fun submitWholeGesture(outcome: GestureOutcome) {
        val session = outcome.session
        when (outcome.type) {
            GestureType.TAP -> submitTap(session)
            GestureType.DRAG -> send(
                Command.Whole(session.id, session.dragPath(), GestureType.DRAG),
            )
        }
    }

    fun submitCancel(gestureId: Long) = send(Command.Cancel(gestureId))

    private fun send(command: Command) {
        val channel = commands ?: return
        if (channel.trySend(command).isFailure) Log.w(TAG, "큐에 넣지 못했습니다")
    }

    // ------------------------------------------------------------------
    // 처리
    // ------------------------------------------------------------------

    private suspend fun handle(command: Command) {
        // 다른 제스처의 스트로크를 들고 있으면 여기서 버린다. 물려받지 않는다.
        val held = chain
        if (held != null && held.gestureId != command.gestureId) {
            chain = null
            onNote(
                "#${held.gestureId} 의 스트로크가 남아 있어 버렸습니다 " +
                    "(#${command.gestureId} 는 처음부터 새로 만듭니다).",
            )
        }

        when (command) {
            is Command.Tap -> {
                chain = null
                dispatchSegment(
                    gestureId = command.gestureId,
                    points = listOf(command.point),
                    phase = TouchPhase.UP,
                    type = GestureType.TAP,
                    willContinue = false,
                    durationOverrideMs = command.durationMs,
                )
            }

            is Command.DragOpen -> {
                chain = null
                dispatchSegment(
                    command.gestureId, command.points, TouchPhase.DOWN,
                    GestureType.DRAG, willContinue = true,
                )
            }

            is Command.DragContinue -> dispatchSegment(
                command.gestureId, command.points, TouchPhase.MOVE,
                GestureType.DRAG, willContinue = true,
            )

            is Command.DragEnd -> {
                dispatchSegment(
                    command.gestureId, command.points, TouchPhase.UP,
                    GestureType.DRAG, willContinue = false,
                )
                chain = null
            }

            is Command.Whole -> {
                chain = null
                dispatchSegment(
                    command.gestureId, command.points, TouchPhase.UP,
                    command.type, willContinue = false,
                )
            }

            is Command.Cancel -> chain = null
        }
    }

    private suspend fun dispatchSegment(
        gestureId: Long,
        points: List<TouchPoint>,
        phase: TouchPhase,
        type: GestureType,
        willContinue: Boolean,
        durationOverrideMs: Long? = null,
    ) {
        if (points.isEmpty()) return
        val service = MirrorAccessibilityService.instance
        val currentTargets = targets
        val masterPoint = points.last()

        val precondition = when {
            service == null -> "접근성 서비스가 연결되어 있지 않습니다."
            currentTargets.isEmpty() -> "보낼 대상이 없습니다."
            else -> null
        }
        if (precondition != null || service == null) {
            fail(gestureId, phase, type, masterPoint, emptyMap(), precondition ?: "알 수 없음")
            return
        }

        if (inputDelayMs > 0) delay(inputDelayMs)

        val plan = buildPlan(points, currentTargets) ?: run {
            fail(gestureId, phase, type, masterPoint, emptyMap(), "좌표를 변환하지 못했습니다.")
            return
        }

        // 주입 좌표가 화면 밖으로 나가면 보내지 않는다. 화면이 바뀐 뒤 옛 좌표가 그대로
        // 쓰이면 엉뚱한 곳이 눌리기 때문이다. 조용히 자르지 않고 거부하고 이유를 남긴다.
        displayBounds?.let { screen ->
            val offScreen = plan.firstNotNullOfOrNull { target ->
                target.screenPath.firstOrNull { point ->
                    point.x < screen.left || point.x >= screen.right ||
                        point.y < screen.top || point.y >= screen.bottom
                }?.let { target to it }
            }
            if (offScreen != null) {
                val (target, point) = offScreen
                fail(
                    gestureId, phase, type, masterPoint, plan.endPoints(),
                    "${target.target.name} 의 주입 좌표 " +
                        "(${point.x.toInt()}, ${point.y.toInt()}) 가 화면 " +
                        "${screen.width}×${screen.height} 밖입니다. " +
                        "화면 구성이 바뀌었을 수 있으니 영역을 다시 확인해주세요.",
                )
                return
            }
        }

        // 대상 수가 시스템 한계를 넘으면 묶어 보낼 수 없다. 조용히 자르지 않고 알린다.
        val maxStrokes = MirrorAccessibilityService.maxStrokeCount
        if (dispatchMode == DispatchMode.COMBINED && plan.size > maxStrokes) {
            fail(
                gestureId, phase, type, masterPoint, plan.endPoints(),
                "이 기기는 한 제스처에 최대 ${maxStrokes}개 손가락만 담을 수 있습니다. " +
                    "대상이 ${plan.size}개입니다. MODE B(하나씩 차례로)를 써보세요.",
            )
            return
        }

        val maxDuration = MirrorAccessibilityService.maxGestureDurationMs
        val duration = durationOverrideMs
            ?.coerceIn(MirrorPathPlanner.MIN_SEGMENT_MS, maxDuration.coerceAtLeast(1L))
            ?: MirrorPathPlanner.segmentDuration(points, maxDuration)

        trace(
            TraceEntry(
                gestureId = gestureId,
                stage = TraceStage.GESTURE_CREATE,
                elapsedNanos = GestureTracer.now(),
                phase = phase,
                targetNames = plan.map { it.target.name },
                targetPoints = plan.endPoints(),
                masterViewX = masterPoint.x,
                masterViewY = masterPoint.y,
                masterScreenX = geometry.toScreenX(masterPoint.x),
                masterScreenY = geometry.toScreenY(masterPoint.y),
                strokeCount = if (dispatchMode == DispatchMode.COMBINED) plan.size else 1,
                durationMs = duration,
                detail = "type=${type.label} points=${points.size}",
            ),
        )

        val groups = when (dispatchMode) {
            DispatchMode.SINGLE -> listOf(listOf(plan.first()))
            DispatchMode.SEQUENTIAL -> plan.map { listOf(it) }
            DispatchMode.COMBINED -> listOf(plan)
        }
        dispatchGroups(
            gestureId, phase, type, masterPoint, groups, duration, willContinue, maxDuration,
        )
    }

    /** 대상 하나에 대해 이번 구간에 그릴 화면 좌표 경로. */
    private class TargetPlan(
        val target: MirrorTarget,
        val index: Int,
        val screenPath: List<MappedPoint>,
    )

    private fun List<TargetPlan>.endPoints(): Map<String, MappedPoint> =
        associate { it.target.name to it.screenPath.last() }

    /**
     * 대상별 화면 좌표 경로를 만든다.
     *
     * 이어붙이는 구간이면 **직전 스트로크가 끝난 점에서 시작**하도록 맨 앞에 끼워 넣는다.
     * 시작점이 다르면 시스템이 이어붙이기를 거부한다. 들고 있는 스트로크가 이번 제스처의
     * 것이 아니면 [handle] 에서 이미 버렸으므로 여기서 섞일 일이 없다.
     */
    private fun buildPlan(
        points: List<TouchPoint>,
        currentTargets: List<MirrorTarget>,
    ): List<TargetPlan>? {
        val previous = chain?.strokes
        val out = ArrayList<TargetPlan>(currentTargets.size)
        currentTargets.forEachIndexed { index, target ->
            val viewPath = MirrorPathPlanner.mapPath(points, target.transformer) ?: return null
            val screenPath = viewPath.map {
                MappedPoint(geometry.toScreenX(it.x), geometry.toScreenY(it.y))
            }
            val cleaned = MirrorPathPlanner.dropDuplicates(screenPath)
            val continued = previous?.getOrNull(index)?.endPoint
            val full = if (continued != null && !continued.sameAs(cleaned.first())) {
                listOf(continued) + cleaned
            } else {
                cleaned
            }
            out += TargetPlan(target, index, full)
        }
        return out
    }

    private suspend fun dispatchGroups(
        gestureId: Long,
        phase: TouchPhase,
        type: GestureType,
        masterPoint: TouchPoint,
        groups: List<List<TargetPlan>>,
        duration: Long,
        willContinue: Boolean,
        maxDuration: Long,
    ) {
        val service = MirrorAccessibilityService.instance ?: return
        val nextStrokes = arrayOfNulls<ActiveStroke>(targets.size)
        var allOk = true
        var lastError: String? = null
        val reported = LinkedHashMap<String, MappedPoint>()

        for (group in groups) {
            val builder = GestureDescription.Builder()
            val builtFor = ArrayList<Pair<TargetPlan, GestureDescription.StrokeDescription>>()

            for (plan in group) {
                val path = Path().apply {
                    moveTo(plan.screenPath.first().x, plan.screenPath.first().y)
                    for (i in 1 until plan.screenPath.size) {
                        lineTo(plan.screenPath[i].x, plan.screenPath[i].y)
                    }
                }
                // 점이 하나뿐이면 moveTo 만 있는 경로가 된다. 공식 문서상 이는
                // "움직이지 않는 터치" 이고, 그것이 바로 TAP 이다.
                val previousStroke = chain?.strokes?.getOrNull(plan.index)?.stroke
                val stroke = try {
                    if (previousStroke != null) {
                        // 이어붙이는 구간은 앞 스트로크에 곧바로 붙어야 하므로 시작 시각이 0 이다.
                        previousStroke.continueStroke(path, 0L, duration, willContinue)
                    } else {
                        // 대상별 지연은 제스처 안에서의 시작 시각으로 표현한다.
                        // 시작 시각 + 길이가 시스템 상한을 넘으면 접수 자체가 거부되므로 자른다.
                        val startTime = plan.target.delayMs
                            .coerceIn(0L, (maxDuration - duration).coerceAtLeast(0L))
                        GestureDescription.StrokeDescription(path, startTime, duration, willContinue)
                    }
                } catch (e: Exception) {
                    // 여기가 이어붙이기 규칙 위반이 드러나는 자리다. 메시지를 그대로 남긴다.
                    allOk = false
                    lastError = "${plan.target.name} 스트로크 생성 실패: " +
                        "${e::class.java.simpleName}: ${e.message}"
                    trace(
                        TraceEntry(
                            gestureId = gestureId,
                            stage = TraceStage.ERROR,
                            elapsedNanos = GestureTracer.now(),
                            phase = phase,
                            targetNames = listOf(plan.target.name),
                            result = TraceResult.EXCEPTION,
                            detail = lastError,
                        ),
                    )
                    null
                }
                if (stroke != null) {
                    builder.addStroke(stroke)
                    builtFor += plan to stroke
                    reported[plan.target.name] = plan.screenPath.last()
                }
            }
            if (builtFor.isEmpty()) continue

            trace(
                TraceEntry(
                    gestureId = gestureId,
                    stage = TraceStage.DISPATCH_REQUEST,
                    elapsedNanos = GestureTracer.now(),
                    phase = phase,
                    targetNames = builtFor.map { it.first.target.name },
                    strokeCount = builtFor.size,
                    durationMs = duration,
                    detail = builtFor.joinToString(" | ") { (plan, _) ->
                        val first = plan.screenPath.first()
                        val last = plan.screenPath.last()
                        "#${plan.index} ${plan.target.name} " +
                            "(${first.x.toInt()},${first.y.toInt()})→" +
                            "(${last.x.toInt()},${last.y.toInt()}) " +
                            "start=${plan.target.delayMs}ms"
                    } + "  overlayTouchable=${overlayTouchable?.invoke()}",
                ),
            )

            var accepted: Boolean? = null
            val requestedAt: Long
            val result: DispatchResult
            // 콜백이 끝내 오지 않는 경우가 있다. 그때 영원히 기다리면 이 코루틴 하나가
            // 막히면서 **그 뒤의 모든 입력이 멈춘다.** 그래서 상한을 두고, 넘으면
            // 성공이 아니라 TimedOut 으로 끊어 기록한다. 문제를 숨기는 것이 아니라
            // 드러내면서 파이프라인을 살려두기 위함이다.
            val waitMs = duration + maxDelayMs(group) + CALLBACK_GRACE_MS
            beforeDispatch?.invoke()
            try {
                requestedAt = GestureTracer.now()
                result = withTimeoutOrNull(waitMs) {
                    service.dispatch(builder.build()) { ok ->
                        accepted = ok
                        trace(
                            TraceEntry(
                                gestureId = gestureId,
                                stage = TraceStage.DISPATCH_RETURN,
                                elapsedNanos = GestureTracer.now(),
                                phase = phase,
                                targetNames = builtFor.map { it.first.target.name },
                                accepted = ok,
                            ),
                        )
                    }
                } ?: DispatchResult.TimedOut(
                    "제스처를 접수했지만 ${waitMs}ms 안에 완료/취소 콜백이 오지 않았습니다.",
                )
            } finally {
                // 예외가 나든 코루틴이 취소되든 반드시 되돌린다.
                afterDispatch?.invoke()
            }
            val callbackAt = GestureTracer.now()

            trace(
                TraceEntry(
                    gestureId = gestureId,
                    stage = TraceStage.CALLBACK,
                    elapsedNanos = callbackAt,
                    phase = phase,
                    targetNames = builtFor.map { it.first.target.name },
                    accepted = accepted,
                    result = when (result) {
                        DispatchResult.Success -> TraceResult.COMPLETED
                        is DispatchResult.Cancelled -> TraceResult.CANCELLED
                        is DispatchResult.Rejected -> TraceResult.REJECTED
                        is DispatchResult.Threw -> TraceResult.EXCEPTION
                        is DispatchResult.TimedOut -> TraceResult.TIMEOUT
                    },
                    durationMs = (callbackAt - requestedAt) / 1_000_000,
                    detail = result.message.ifBlank { null },
                ),
            )

            if (result.isSuccess) {
                builtFor.forEach { (plan, stroke) ->
                    nextStrokes[plan.index] = ActiveStroke(stroke, plan.screenPath.last())
                }
            } else {
                allOk = false
                lastError = result.message
            }
        }

        // 이어붙일 스트로크는 **이번 제스처 번호와 함께** 보관한다. 다음 제스처가 오면
        // handle() 첫머리에서 번호가 달라 곧바로 버려진다.
        chain = if (willContinue && allOk) {
            nextStrokes.toList().filterNotNull()
                .takeIf { it.size == targets.size }
                ?.let { ActiveChain(gestureId, it) }
        } else {
            null
        }

        val latency = (System.currentTimeMillis() - masterPoint.timestamp).coerceAtLeast(0L)
        onRecord(
            MirrorRecord(
                timestamp = System.currentTimeMillis(),
                gestureId = gestureId,
                gestureType = type,
                phase = phase,
                masterX = masterPoint.x,
                masterY = masterPoint.y,
                targets = reported,
                latencyMs = latency,
                success = allOk,
                error = lastError,
            ),
        )
    }

    private fun fail(
        gestureId: Long,
        phase: TouchPhase,
        type: GestureType,
        master: TouchPoint,
        targetPoints: Map<String, MappedPoint>,
        reason: String,
    ) {
        trace(
            TraceEntry(
                gestureId = gestureId,
                stage = TraceStage.ERROR,
                elapsedNanos = GestureTracer.now(),
                phase = phase,
                targetNames = targets.map { it.name },
                result = TraceResult.REJECTED,
                detail = reason,
            ),
        )
        onRecord(
            MirrorRecord(
                timestamp = System.currentTimeMillis(),
                gestureId = gestureId,
                gestureType = type,
                phase = phase,
                masterX = master.x,
                masterY = master.y,
                targets = targetPoints,
                latencyMs = 0,
                success = false,
                error = reason,
            ),
        )
    }

    private fun trace(entry: TraceEntry) = tracer.add(entry)

    /** 이 묶음에서 가장 늦게 시작하는 대상의 지연. 대기 상한 계산에 쓴다. */
    private fun maxDelayMs(group: List<TargetPlan>): Long =
        group.maxOfOrNull { it.target.delayMs.coerceAtLeast(0L) } ?: 0L

    private companion object {
        const val TAG = "GestureDispatcher"

        /** 제스처 길이 위에 얹어 주는 콜백 대기 여유. */
        const val CALLBACK_GRACE_MS = 3_000L
    }
}

/** 두 점이 사실상 같은 자리인지. 이어붙이기 시작점 비교에 쓴다. */
internal fun MappedPoint.sameAs(other: MappedPoint, epsilon: Float = 0.01f): Boolean =
    kotlin.math.abs(x - other.x) < epsilon && kotlin.math.abs(y - other.y) < epsilon
