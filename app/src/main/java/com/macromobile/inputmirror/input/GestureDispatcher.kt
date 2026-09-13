package com.macromobile.inputmirror.input

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.macromobile.inputmirror.model.DispatchMode
import com.macromobile.inputmirror.model.MirrorMode
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

private sealed interface Command {
    data class Down(val point: TouchPoint) : Command
    data class Move(val points: List<TouchPoint>) : Command
    data class Up(val points: List<TouchPoint>) : Command
    data class WholeStroke(val points: List<TouchPoint>) : Command
    data object Cancel : Command
}

/** 대상별로 진행 중인 스트로크와 그 끝점을 함께 들고 있는다. */
private class ActiveStroke(
    val stroke: GestureDescription.StrokeDescription,
    /** 이 스트로크가 끝난 지점(화면 좌표). 이어붙일 경로는 **반드시 여기서 시작**해야 한다. */
    val endPoint: MappedPoint,
)

/**
 * 마스터의 터치를 대상들에 주입한다.
 *
 * 명령을 채널에 넣고 코루틴 하나가 순서대로 꺼내 처리하므로 입력 순서가 바뀌지 않는다.
 * 제스처는 앞의 것이 끝나야 다음을 보낼 수 있어 매 전송의 완료를 기다린다.
 *
 * 좌표는 다음 순서로 옮긴다.
 * ```
 * 마스터 View 좌표 → (CoordinateTransformer) → 대상 View 좌표
 *                 → (ScreenGeometry)        → 대상 Screen 좌표 → dispatchGesture
 * ```
 * 마지막 단계를 빠뜨리면 View 가 화면 원점에 있지 않은 만큼 어긋난 곳에 주입된다.
 */
class GestureDispatcher(
    private val scope: CoroutineScope,
    private val tracer: GestureTracer,
    private val onRecord: (MirrorRecord) -> Unit,
) {
    private var commands: Channel<Command>? = null
    private var active: List<ActiveStroke>? = null
    private val pending = ArrayList<TouchPoint>()

    @Volatile
    var targets: List<MirrorTarget> = emptyList()

    @Volatile
    var mode: MirrorMode = MirrorMode.STREAMING

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
                    trace(
                        TraceEntry(
                            gestureId = 0,
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
        active = null
        pending.clear()
    }

    fun onDown(point: TouchPoint) = send(Command.Down(point))
    fun onMove(points: List<TouchPoint>) { if (points.isNotEmpty()) send(Command.Move(points)) }
    fun onUp(points: List<TouchPoint>) = send(Command.Up(points))
    fun onCancel() = send(Command.Cancel)
    fun onWholeStroke(points: List<TouchPoint>) = send(Command.WholeStroke(points))

    private fun send(command: Command) {
        val channel = commands ?: return
        if (channel.trySend(command).isFailure) Log.w(TAG, "큐에 넣지 못했습니다")
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Down -> {
                pending.clear()
                active = null
                if (mode == MirrorMode.STREAMING) {
                    dispatchSegment(listOf(command.point), TouchPhase.DOWN, willContinue = true)
                }
            }

            is Command.Move -> {
                if (mode != MirrorMode.STREAMING) return
                pending += command.points
                val batch = ArrayList(pending)
                pending.clear()
                dispatchSegment(batch, TouchPhase.MOVE, willContinue = true)
            }

            is Command.Up -> {
                if (mode != MirrorMode.STREAMING) return
                val batch = ArrayList(pending).apply { addAll(command.points) }
                pending.clear()
                dispatchSegment(batch, TouchPhase.UP, willContinue = false)
                active = null
            }

            is Command.WholeStroke -> {
                active = null
                dispatchSegment(command.points, TouchPhase.UP, willContinue = false)
            }

            Command.Cancel -> {
                pending.clear()
                active = null
            }
        }
    }

    // ------------------------------------------------------------------
    // 전송
    // ------------------------------------------------------------------

    private suspend fun dispatchSegment(
        points: List<TouchPoint>,
        phase: TouchPhase,
        willContinue: Boolean,
    ) {
        if (points.isEmpty()) return
        val gestureId = tracer.newGestureId()
        val service = MirrorAccessibilityService.instance
        val currentTargets = targets
        val masterPoint = points.last()

        val precondition = when {
            service == null -> "접근성 서비스가 연결되어 있지 않습니다."
            currentTargets.isEmpty() -> "보낼 대상이 없습니다."
            else -> null
        }
        if (precondition != null || service == null) {
            fail(gestureId, phase, masterPoint, emptyMap(), precondition ?: "알 수 없음", 0)
            return
        }

        if (inputDelayMs > 0) delay(inputDelayMs)

        val plan = buildPlan(points, currentTargets) ?: run {
            fail(gestureId, phase, masterPoint, emptyMap(), "좌표를 변환하지 못했습니다.", 0)
            return
        }

        // 대상 수가 시스템 한계를 넘으면 묶어 보낼 수 없다. 조용히 자르지 않고 알린다.
        val maxStrokes = MirrorAccessibilityService.maxStrokeCount
        if (dispatchMode == DispatchMode.COMBINED && plan.size > maxStrokes) {
            fail(
                gestureId, phase, masterPoint, plan.endPoints(), 
                "이 기기는 한 제스처에 최대 ${maxStrokes}개 손가락만 담을 수 있습니다. " +
                    "대상이 ${plan.size}개입니다. MODE B(하나씩 차례로)를 써보세요.",
                0,
            )
            return
        }

        val duration = MirrorPathPlanner.segmentDuration(
            points, MirrorAccessibilityService.maxGestureDurationMs,
        )

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
            ),
        )

        when (dispatchMode) {
            DispatchMode.SINGLE -> dispatchGroups(
                gestureId, phase, masterPoint, listOf(listOf(plan.first())), duration, willContinue,
            )

            DispatchMode.SEQUENTIAL -> dispatchGroups(
                gestureId, phase, masterPoint, plan.map { listOf(it) }, duration, willContinue,
            )

            DispatchMode.COMBINED -> dispatchGroups(
                gestureId, phase, masterPoint, listOf(plan), duration, willContinue,
            )
        }
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
     * 시작점이 다르면 시스템이 이어붙이기를 거부한다.
     */
    private fun buildPlan(points: List<TouchPoint>, currentTargets: List<MirrorTarget>): List<TargetPlan>? {
        val previous = active
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
        masterPoint: TouchPoint,
        groups: List<List<TargetPlan>>,
        duration: Long,
        willContinue: Boolean,
    ) {
        val service = MirrorAccessibilityService.instance ?: return
        val nextActive = arrayOfNulls<ActiveStroke>(targets.size)
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
                val previousStroke = active?.getOrNull(plan.index)?.stroke
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
                    nextActive[plan.index] = ActiveStroke(stroke, plan.screenPath.last())
                }
            } else {
                allOk = false
                lastError = result.message
            }
        }

        active = if (willContinue && allOk) nextActive.toList().filterNotNull()
            .takeIf { it.size == targets.size } else null

        val latency = (System.currentTimeMillis() - masterPoint.timestamp).coerceAtLeast(0L)
        onRecord(
            MirrorRecord(
                timestamp = System.currentTimeMillis(),
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
        master: TouchPoint,
        targetPoints: Map<String, MappedPoint>,
        reason: String,
        latency: Long,
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
                phase = phase,
                masterX = master.x,
                masterY = master.y,
                targets = targetPoints,
                latencyMs = latency,
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
