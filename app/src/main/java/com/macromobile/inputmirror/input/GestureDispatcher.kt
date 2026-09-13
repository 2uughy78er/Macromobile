package com.macromobile.inputmirror.input

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.macromobile.inputmirror.model.MirrorMode
import com.macromobile.inputmirror.service.DispatchResult
import com.macromobile.inputmirror.service.MirrorAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 대상 하나. 이름과 좌표 변환기를 함께 들고 다닌다. */
data class MirrorTarget(
    val id: String,
    val name: String,
    val transformer: CoordinateTransformer,
)

/** 전송기에 들어오는 명령. 들어온 순서 그대로 처리된다. */
private sealed interface Command {
    data class Down(val point: TouchPoint) : Command
    data class Move(val points: List<TouchPoint>) : Command
    data class Up(val points: List<TouchPoint>) : Command
    data class WholeStroke(val points: List<TouchPoint>) : Command
    data object Cancel : Command
}

/**
 * 마스터의 터치를 대상들에 실제로 주입한다.
 *
 * 요구사항 7·8번(큐 방식, 순서 보장)을 위해 명령을 채널에 넣고 **단 하나의 코루틴이
 * 순서대로** 꺼내 처리한다. 제스처는 앞의 것이 끝나야 다음을 보낼 수 있기 때문에
 * 매 전송의 완료를 기다린다.
 *
 * 대상이 여럿이어도 **한 제스처에 스트로크를 여러 개 담아 한 번에** 보낸다. 그래야
 * 대상들이 동시에 같은 순간에 입력을 받는다. 담을 수 있는 스트로크 수는 시스템이
 * 정하며([MirrorAccessibilityService.maxStrokeCount]) 그게 곧 대상 수의 상한이다.
 */
class GestureDispatcher(
    private val scope: CoroutineScope,
    private val onRecord: (MirrorRecord) -> Unit,
) {
    private var commands: Channel<Command>? = null

    /** 스트리밍 전송에서 각 대상의 직전 스트로크. 여기에 이어붙인다. */
    private var activeStrokes: List<GestureDescription.StrokeDescription>? = null

    /** 아직 보내지 않은 경로 점. 전송이 밀리면 여기 쌓였다가 한 번에 나간다. */
    private val pending = ArrayList<TouchPoint>()

    @Volatile
    var targets: List<MirrorTarget> = emptyList()

    @Volatile
    var mode: MirrorMode = MirrorMode.STREAMING

    @Volatile
    var inputDelayMs: Long = 0L

    /** 전송기를 켠다. 큐를 비우고 처리 코루틴을 하나 띄운다. */
    fun start() {
        stop()
        val channel = Channel<Command>(Channel.UNLIMITED)
        commands = channel
        scope.launch {
            for (command in channel) {
                runCatching { handle(command) }
                    .onFailure { Log.e(TAG, "명령 처리 실패", it) }
            }
        }
    }

    fun stop() {
        commands?.close()
        commands = null
        activeStrokes = null
        pending.clear()
    }

    // ------------------------------------------------------------------
    // 마스터 쪽에서 불러주는 입구
    // ------------------------------------------------------------------

    fun onDown(point: TouchPoint) = send(Command.Down(point))

    fun onMove(points: List<TouchPoint>) {
        if (points.isEmpty()) return
        send(Command.Move(points))
    }

    fun onUp(points: List<TouchPoint>) = send(Command.Up(points))

    fun onCancel() = send(Command.Cancel)

    /** 일괄 전송 방식에서 손가락을 뗀 뒤 경로 전체를 한 번에 보낸다. */
    fun onWholeStroke(points: List<TouchPoint>) = send(Command.WholeStroke(points))

    private fun send(command: Command) {
        val channel = commands ?: return
        val result = channel.trySend(command)
        if (result.isFailure) Log.w(TAG, "큐에 넣지 못했습니다: $command")
    }

    // ------------------------------------------------------------------
    // 실제 처리
    // ------------------------------------------------------------------

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Down -> {
                pending.clear()
                activeStrokes = null
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
                activeStrokes = null
            }

            is Command.WholeStroke -> {
                activeStrokes = null
                dispatchSegment(command.points, TouchPhase.UP, willContinue = false)
            }

            Command.Cancel -> {
                pending.clear()
                activeStrokes = null
            }
        }
    }

    /**
     * 한 구간을 모든 대상에 동시에 보낸다.
     *
     * 스트로크를 대상 수만큼 만들어 **하나의 제스처**로 묶는다.
     */
    private suspend fun dispatchSegment(
        points: List<TouchPoint>,
        phase: TouchPhase,
        willContinue: Boolean,
    ) {
        if (points.isEmpty()) return
        val service = MirrorAccessibilityService.instance
        val currentTargets = targets
        val masterPoint = points.last()

        if (service == null) {
            record(phase, masterPoint, emptyMap(), false, "접근성 서비스가 연결되어 있지 않습니다.")
            return
        }
        if (currentTargets.isEmpty()) {
            record(phase, masterPoint, emptyMap(), false, "보낼 대상이 없습니다.")
            return
        }
        val maxStrokes = MirrorAccessibilityService.maxStrokeCount
        if (!MirrorPathPlanner.withinStrokeLimit(currentTargets.size, maxStrokes)) {
            record(
                phase, masterPoint, emptyMap(), false,
                "이 기기는 한 번에 최대 ${maxStrokes}개까지만 동시 입력할 수 있습니다. " +
                    "대상이 ${currentTargets.size}개입니다.",
            )
            return
        }

        if (inputDelayMs > 0) delay(inputDelayMs)

        val maxDuration = MirrorAccessibilityService.maxGestureDurationMs
        val duration = MirrorPathPlanner.segmentDuration(points, maxDuration)

        val builder = GestureDescription.Builder()
        val mappedByTarget = LinkedHashMap<String, MappedPoint>()
        val nextStrokes = ArrayList<GestureDescription.StrokeDescription>(currentTargets.size)
        val previous = activeStrokes

        currentTargets.forEachIndexed { index, target ->
            val mapped = MirrorPathPlanner.mapPath(points, target.transformer)
            if (mapped == null) {
                record(
                    phase, masterPoint, mappedByTarget, false,
                    "'${target.name}' 의 창 크기를 몰라 좌표를 변환하지 못했습니다.",
                )
                return
            }
            val cleaned = MirrorPathPlanner.dropDuplicates(mapped)
            mappedByTarget[target.name] = cleaned.last()

            val path = Path().apply {
                moveTo(cleaned.first().x, cleaned.first().y)
                for (i in 1 until cleaned.size) lineTo(cleaned[i].x, cleaned[i].y)
            }

            val stroke = if (previous != null && index < previous.size) {
                // 진행 중인 스트로크에 이어붙인다. 그래야 하나의 드래그로 인식된다.
                previous[index].continueStroke(path, 0L, duration, willContinue)
            } else {
                GestureDescription.StrokeDescription(path, 0L, duration, willContinue)
            }
            builder.addStroke(stroke)
            nextStrokes += stroke
        }

        val result = service.dispatch(builder.build())
        activeStrokes = if (willContinue) nextStrokes else null

        val latency = (System.currentTimeMillis() - masterPoint.timestamp).coerceAtLeast(0L)
        when (result) {
            is DispatchResult.Success -> record(phase, masterPoint, mappedByTarget, true, null, latency)
            is DispatchResult.Failed -> {
                // 이어붙이던 흐름이 끊겼으므로 다음 구간은 새 스트로크로 시작한다.
                activeStrokes = null
                record(phase, masterPoint, mappedByTarget, false, result.reason, latency)
            }
        }
    }

    private fun record(
        phase: TouchPhase,
        master: TouchPoint,
        targets: Map<String, MappedPoint>,
        success: Boolean,
        error: String?,
        latencyMs: Long = 0L,
    ) {
        onRecord(
            MirrorRecord(
                timestamp = System.currentTimeMillis(),
                phase = phase,
                masterX = master.x,
                masterY = master.y,
                targets = targets,
                latencyMs = latencyMs,
                success = success,
                error = error,
            ),
        )
    }

    private companion object {
        const val TAG = "GestureDispatcher"
    }
}
