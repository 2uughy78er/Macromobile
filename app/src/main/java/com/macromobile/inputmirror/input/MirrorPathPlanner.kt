package com.macromobile.inputmirror.input

/**
 * 마스터의 터치 경로를 대상 창의 경로로 옮기는 계산.
 *
 * 안드로이드 API 를 쓰지 않는 순수 계산만 모아두어 단위 테스트로 규칙을 고정한다.
 * 실제 제스처를 만드는 일은 [GestureDispatcher] 가 한다.
 */
object MirrorPathPlanner {

    /** 제스처 한 구간의 최소 길이. 0 이면 시스템이 받아주지 않는다. */
    const val MIN_SEGMENT_MS = 1L

    /**
     * 마스터 경로를 대상 좌표 경로로 바꾼다.
     *
     * 변환할 수 없는 점이 하나라도 있으면 **전체를 포기한다.** 일부만 옮기면 경로가
     * 엉뚱하게 꺾여서 의도하지 않은 곳을 누르게 되기 때문이다.
     */
    fun mapPath(
        points: List<TouchPoint>,
        transformer: CoordinateTransformer,
    ): List<MappedPoint>? {
        if (points.isEmpty() || !transformer.isUsable) return null
        val out = ArrayList<MappedPoint>(points.size)
        for (p in points) {
            out += transformer.mapClamped(p.x, p.y) ?: return null
        }
        return out
    }

    /**
     * 이어지는 같은 위치의 점을 걷어낸다.
     *
     * 손가락을 가만히 대고 있으면 같은 좌표가 수십 개 쌓이는데, 그대로 경로로 만들면
     * 제스처만 무거워지고 결과는 같다. 첫 점은 반드시 남긴다.
     */
    fun dropDuplicates(points: List<MappedPoint>, epsilon: Float = 0.5f): List<MappedPoint> {
        if (points.size <= 1) return points
        val out = ArrayList<MappedPoint>(points.size)
        out += points.first()
        for (i in 1 until points.size) {
            val prev = out.last()
            val cur = points[i]
            if (kotlin.math.abs(cur.x - prev.x) > epsilon ||
                kotlin.math.abs(cur.y - prev.y) > epsilon
            ) {
                out += cur
            }
        }
        return out
    }

    /**
     * 구간이 실제로 걸린 시간. 제스처 길이로 쓴다.
     *
     * 0 이하이면 시스템이 거부하므로 최소값으로 올리고, 시스템 상한도 넘지 않게 자른다.
     */
    fun segmentDuration(points: List<TouchPoint>, maxDurationMs: Long): Long {
        if (points.size < 2) return MIN_SEGMENT_MS
        val raw = points.last().timestamp - points.first().timestamp
        return raw.coerceIn(MIN_SEGMENT_MS, maxDurationMs.coerceAtLeast(MIN_SEGMENT_MS))
    }

    /**
     * 대상이 시스템이 허용하는 손가락 수를 넘는지 확인한다.
     *
     * 한 제스처에 넣을 수 있는 스트로크 수가 곧 동시에 조작할 수 있는 대상 수의 상한이다.
     * 넘으면 조용히 자르지 않고 사용자에게 알려야 한다(요구사항 9번).
     */
    fun withinStrokeLimit(targetCount: Int, maxStrokeCount: Int): Boolean =
        targetCount in 1..maxStrokeCount
}
