package com.macromobile.inputmirror.input

import android.os.SystemClock

/** 제스처 한 건의 생애 단계. 요구사항 4단계의 로그 형식을 그대로 따른다. */
enum class TraceStage {
    GESTURE_CREATE,
    DISPATCH_REQUEST,
    DISPATCH_RETURN,
    CALLBACK,
    ERROR,
}

/** 제스처가 어떻게 끝났는지. */
enum class TraceResult {
    PENDING, COMPLETED, CANCELLED, REJECTED, EXCEPTION, TIMEOUT
}

/**
 * 추적 기록 한 줄.
 *
 * 시각은 반드시 **monotonic clock**([SystemClock.elapsedRealtimeNanos])으로 잰다.
 * 벽시계(`System.currentTimeMillis`)는 시간 보정으로 뒤로 갈 수 있어 지연 측정에 쓸 수 없다.
 */
data class TraceEntry(
    val gestureId: Long,
    val stage: TraceStage,
    val elapsedNanos: Long,
    val phase: TouchPhase,
    val targetNames: List<String>,
    /** 대상별 주입할 화면 좌표. */
    val targetPoints: Map<String, MappedPoint> = emptyMap(),
    val masterViewX: Float = 0f,
    val masterViewY: Float = 0f,
    val masterScreenX: Float = 0f,
    val masterScreenY: Float = 0f,
    val accepted: Boolean? = null,
    val result: TraceResult = TraceResult.PENDING,
    val strokeCount: Int = 0,
    val durationMs: Long = 0,
    val detail: String? = null,
) {
    /** 기준 시각 대비 경과 밀리초. */
    fun msSince(baseNanos: Long): Double = (elapsedNanos - baseNanos) / 1_000_000.0

    fun format(baseNanos: Long): String = buildString {
        append("%8.2fms ".format(msSince(baseNanos)))
        append("#$gestureId ")
        append(stage.name.padEnd(16))
        append(phase.label.removePrefix("ACTION_").padEnd(6))
        when (stage) {
            TraceStage.GESTURE_CREATE -> {
                append("master view(${masterViewX.toInt()},${masterViewY.toInt()}) ")
                append("screen(${masterScreenX.toInt()},${masterScreenY.toInt()}) ")
                append("strokes=$strokeCount dur=${durationMs}ms ")
                append(targetPoints.entries.joinToString(" ") { (n, p) ->
                    "$n→(${p.x.toInt()},${p.y.toInt()})"
                })
            }

            TraceStage.DISPATCH_REQUEST -> append("targets=${targetNames.joinToString(",")}")
            TraceStage.DISPATCH_RETURN -> append("accepted=$accepted")
            TraceStage.CALLBACK -> append("result=$result")
            TraceStage.ERROR -> append("result=$result")
        }
        detail?.let { append("  | $it") }
    }
}

/**
 * 제스처 추적 버퍼.
 *
 * "실패했다"만으로는 원인을 모른다. 만들어질 때·보낼 때·돌아올 때·콜백이 올 때를 각각
 * 기록해 두면 어디서 끊겼는지 눈으로 확인할 수 있다.
 */
class GestureTracer(private val capacity: Int = 600) {

    private val entries = ArrayDeque<TraceEntry>()
    private var nextId = 1L

    /** 세션 시작 시각. 모든 경과 시간의 기준. */
    var baseNanos: Long = SystemClock.elapsedRealtimeNanos()
        private set

    @Synchronized
    fun reset() {
        entries.clear()
        nextId = 1L
        baseNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized
    fun newGestureId(): Long = nextId++

    @Synchronized
    fun add(entry: TraceEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<TraceEntry> = entries.toList()

    /** 사람이 읽을 수 있는 전체 기록. 로그 파일에 그대로 쓴다. */
    @Synchronized
    fun dump(): String = entries.joinToString("\n") { it.format(baseNanos) }

    companion object {
        fun now(): Long = SystemClock.elapsedRealtimeNanos()
    }
}
