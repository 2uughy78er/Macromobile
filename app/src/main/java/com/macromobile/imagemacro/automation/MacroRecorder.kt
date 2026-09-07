package com.macromobile.imagemacro.automation

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.RefPoint
import com.macromobile.imagemacro.vision.CoordinateMapper

/**
 * 사용자가 실제로 한 동작 하나. 좌표는 **실제 화면 픽셀** 기준이다.
 *
 * @param maxDistancePx 손가락이 시작점에서 가장 멀리 벗어난 거리. 톡 누른 것과 끌어당긴 것을 가른다.
 */
data class RecordedGesture(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val startedAt: Long,
    val endedAt: Long,
    val maxDistancePx: Float,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
}

/**
 * 녹화한 동작을 매크로 단계로 옮긴다.
 *
 * 이미지를 쓰지 않고 "내가 한 그대로" 재생하는 단계를 만든다. 좌표는 기준 해상도로
 * 바꿔 저장하므로 해상도가 다른 기기에서도 같은 위치를 누른다.
 *
 * 동작 사이의 빈 시간도 [ActionType.WAIT] 단계로 남긴다. 그래야 화면이 넘어가기를
 * 기다렸다가 다음을 누르는 원래 흐름이 그대로 재현된다.
 */
object MacroRecorder {

    /** 이 거리 안에서 끝나면 끌기가 아니라 누르기로 본다(dp). */
    const val TAP_SLOP_DP = 12f

    /** 이 시간 이상 누르고 있으면 길게 누르기로 본다. */
    const val LONG_PRESS_MS = 400L

    /** 이보다 짧은 틈은 굳이 대기 단계로 만들지 않는다. */
    const val MIN_GAP_MS = 250L

    /** 대기 단계 하나의 최대 길이. 잠깐 딴짓한 시간까지 그대로 넣지 않는다. */
    const val MAX_WAIT_MS = 30_000L

    /**
     * 녹화한 동작들을 단계 목록으로 바꾼다.
     *
     * @param gestures 녹화된 순서 그대로의 동작들(화면 픽셀 좌표)
     * @param mapper 화면 좌표를 기준 해상도 좌표로 되돌릴 변환기
     * @param density 화면 밀도. 기기마다 다른 "손가락 흔들림"을 같은 기준으로 재기 위해 쓴다.
     * @param startIndex 이름을 매길 때 쓸 시작 번호
     */
    fun toSteps(
        gestures: List<RecordedGesture>,
        mapper: CoordinateMapper,
        density: Float,
        startIndex: Int = 1,
    ): List<MacroStep> {
        if (gestures.isEmpty()) return emptyList()
        val slopPx = TAP_SLOP_DP * density.coerceAtLeast(0.5f)
        val steps = ArrayList<MacroStep>(gestures.size * 2)
        var previousEnd = 0L
        var number = startIndex

        gestures.forEach { g ->
            if (previousEnd > 0L) {
                val gap = (g.startedAt - previousEnd).coerceAtLeast(0L)
                if (gap >= MIN_GAP_MS) {
                    steps += MacroStep(
                        type = ActionType.WAIT,
                        name = "녹화 $number",
                        waitMs = gap.coerceAtMost(MAX_WAIT_MS),
                        afterDelayMs = 0L,
                    )
                    number++
                }
            }
            steps += toStep(g, mapper, slopPx, "녹화 $number")
            number++
            previousEnd = g.endedAt
        }
        return steps
    }

    private fun toStep(
        g: RecordedGesture,
        mapper: CoordinateMapper,
        slopPx: Float,
        name: String,
    ): MacroStep {
        val start = mapper.toRefPoint(g.startX, g.startY)
        return if (g.maxDistancePx <= slopPx) {
            // 제자리에서 끝났다 → 누르기. 오래 붙잡고 있었으면 길게 누르기.
            MacroStep(
                type = ActionType.TAP,
                name = name,
                point = start,
                tapHoldMs = if (g.durationMs >= LONG_PRESS_MS) g.durationMs else 0L,
                afterDelayMs = 0L,
            )
        } else {
            MacroStep(
                type = ActionType.SWIPE,
                name = name,
                swipeStart = start,
                swipeEnd = mapper.toRefPoint(g.endX, g.endY),
                swipeDurationMs = g.durationMs.coerceIn(1L, 60_000L),
                afterDelayMs = 0L,
            )
        }
    }

    /**
     * 이 동작이 "누르기"인지 "끌기"인지 판단한다.
     *
     * 녹화 저장과 되돌려주기(replay)가 같은 기준을 쓰도록 한 곳에 둔다.
     */
    fun isTap(g: RecordedGesture, density: Float): Boolean =
        g.maxDistancePx <= TAP_SLOP_DP * density.coerceAtLeast(0.5f)

    /** 두 점 사이 거리. 녹화 중 손가락이 얼마나 움직였는지 재는 데 쓴다. */
    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = (x2 - x1).toFloat()
        val dy = (y2 - y1).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 기준 해상도가 비어 있는 매크로를 위해 현재 화면 크기를 쓰는 변환기를 만든다. */
    fun mapperFor(referenceWidth: Int, referenceHeight: Int, screenWidth: Int, screenHeight: Int) =
        CoordinateMapper(
            referenceWidth = if (referenceWidth > 0) referenceWidth else screenWidth,
            referenceHeight = if (referenceHeight > 0) referenceHeight else screenHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
}
