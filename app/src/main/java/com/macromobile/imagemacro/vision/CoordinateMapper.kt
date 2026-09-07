package com.macromobile.imagemacro.vision

import com.macromobile.imagemacro.model.RefPoint
import com.macromobile.imagemacro.model.Roi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 기준 해상도 좌표 ↔ 실제 화면 좌표 변환.
 *
 * 기준 해상도와 화면 비율이 다르면 letterbox / pillarbox 가 생기므로,
 * 배율은 가로·세로 중 **작은 쪽**을 쓰고 남는 여백은 가운데 정렬 offset 으로 처리한다.
 * (기존 PC 버전의 `min(sw/refW, sh/refH)` 규칙과 동일하다.)
 *
 * 기준 해상도가 0 이하이면 보정 없이 1:1 로 취급한다.
 */
class CoordinateMapper(
    val referenceWidth: Int,
    val referenceHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    /** 기준 해상도 → 화면 배율. */
    val scale: Float = run {
        if (referenceWidth <= 0 || referenceHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            1f
        } else {
            min(
                screenWidth.toFloat() / referenceWidth,
                screenHeight.toFloat() / referenceHeight,
            )
        }
    }

    /** 가운데 정렬 여백(가로). */
    val offsetX: Float =
        if (referenceWidth <= 0) 0f else (screenWidth - referenceWidth * scale) / 2f

    /** 가운데 정렬 여백(세로). */
    val offsetY: Float =
        if (referenceHeight <= 0) 0f else (screenHeight - referenceHeight * scale) / 2f

    val isIdentity: Boolean
        get() = scale in 0.999f..1.001f && offsetX in -0.5f..0.5f && offsetY in -0.5f..0.5f

    fun toScreenX(refX: Int): Int = (refX * scale + offsetX).roundToInt()

    fun toScreenY(refY: Int): Int = (refY * scale + offsetY).roundToInt()

    fun toScreen(point: RefPoint): Pair<Int, Int> = toScreenX(point.x) to toScreenY(point.y)

    fun toRefX(screenX: Int): Int =
        if (scale <= 0f) screenX else ((screenX - offsetX) / scale).roundToInt()

    fun toRefY(screenY: Int): Int =
        if (scale <= 0f) screenY else ((screenY - offsetY) / scale).roundToInt()

    fun toRefPoint(screenX: Int, screenY: Int): RefPoint = RefPoint(toRefX(screenX), toRefY(screenY))

    /** 길이(거리)만 변환한다. offset 은 더하지 않는다. */
    fun scaleLength(refLength: Int): Int = (refLength * scale).roundToInt()

    /**
     * 기준 좌표 ROI 를 실제 화면 픽셀 사각형으로 변환하고 화면 밖으로 나가지 않게 자른다.
     *
     * 유효한 영역이 남지 않으면 null 을 돌려준다.
     */
    fun toScreenRect(roi: Roi?): ScreenRect? {
        if (roi == null || !roi.isValid) return null
        var left = toScreenX(roi.x)
        var top = toScreenY(roi.y)
        var right = toScreenX(roi.right)
        var bottom = toScreenY(roi.bottom)

        left = max(0, min(left, screenWidth))
        top = max(0, min(top, screenHeight))
        right = max(0, min(right, screenWidth))
        bottom = max(0, min(bottom, screenHeight))

        if (right - left < 1 || bottom - top < 1) return null
        return ScreenRect(left, top, right - left, bottom - top)
    }
}

/** 실제 화면 픽셀 기준 사각형. */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}
