package com.macromobile.inputmirror.input

/** 터치 한 점(실제 화면 픽셀 + 발생 시각). */
data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
)

/** 터치가 어떤 단계인지. 화면 표시와 로그에 그대로 쓴다. */
enum class TouchPhase {
    DOWN, MOVE, UP, CANCEL;

    val label: String
        get() = when (this) {
            DOWN -> "ACTION_DOWN"
            MOVE -> "ACTION_MOVE"
            UP -> "ACTION_UP"
            CANCEL -> "ACTION_CANCEL"
        }
}

/**
 * 마스터에서 진행 중인 한 번의 터치.
 *
 * 손가락을 대는 순간 만들어져 뗄 때까지 경로를 쌓는다. 요구사항 8번의
 * "순서와 시간 간격을 그대로 유지"를 위해 시각을 함께 담는다.
 */
class TouchStroke(down: TouchPoint) {
    private val _points = ArrayList<TouchPoint>(64).apply { add(down) }

    val points: List<TouchPoint> get() = _points
    val start: TouchPoint get() = _points.first()
    val last: TouchPoint get() = _points.last()
    val durationMs: Long get() = (last.timestamp - start.timestamp).coerceAtLeast(0L)

    /** 시작점에서 가장 멀리 벗어난 거리. 누르기와 끌기를 가른다. */
    var maxDistance: Float = 0f
        private set

    fun add(point: TouchPoint) {
        _points += point
        val dx = point.x - start.x
        val dy = point.y - start.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d > maxDistance) maxDistance = d
    }

    /** 아직 대상으로 보내지 않은 구간을 잘라낸다. 스트리밍 전송에 쓴다. */
    fun segmentFrom(index: Int): List<TouchPoint> =
        if (index >= _points.size) emptyList() else _points.subList(index, _points.size).toList()

    val size: Int get() = _points.size
}

/** 미러링 결과 한 줄. 화면 표시와 로그 파일에 함께 쓴다. */
data class MirrorRecord(
    val timestamp: Long,
    val phase: TouchPhase,
    val masterX: Float,
    val masterY: Float,
    /** 대상 이름 → 변환된 좌표. 변환 불가면 값이 없다. */
    val targets: Map<String, MappedPoint>,
    /** 마스터 입력 시각과 실제 전송 시각의 차이(ms). */
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null,
)
