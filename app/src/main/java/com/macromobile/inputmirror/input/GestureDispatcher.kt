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

    fun start() {
        stop()
        val channel = Channel<Command>(Channel.UNLIMITED)
        commands = channel
        scope.launch {
            for (command in channel) {
                runCatching { handle(command) }.onFailure { error ->
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
        dispatchGroups(gestureId, phase, type, masterPoint, groups, duration, willContinue)
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
                        previousStroke.continueStroke(path, 0L, duration, willContinue)
                    } else {
                        GestureDescription.StrokeDescription(path, 0L, duration, willContinue)
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
                ),
            )

            var accepted: Boolean? = null
            val requestedAt = GestureTracer.now()
            val result = service.dispatch(builder.build()) { ok ->
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

    private companion object {
        const val TAG = "GestureDispatcher"
    }
}

/** 두 점이 사실상 같은 자리인지. 이어붙이기 시작점 비교에 쓴다. */
internal fun MappedPoint.sameAs(other: MappedPoint, epsilon: Float = 0.01f): Boolean =
    kotlin.math.abs(x - other.x) < epsilon && kotlin.math.abs(y - other.y) < epsilon
