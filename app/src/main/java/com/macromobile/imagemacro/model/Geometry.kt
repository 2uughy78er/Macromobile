package com.macromobile.imagemacro.model

import kotlinx.serialization.Serializable

/**
 * 기준 해상도 좌표계에서의 점.
 *
 * 모든 좌표는 매크로의 [Macro.referenceWidth] / [Macro.referenceHeight] 를 기준으로 저장하고,
 * 실행 시점에 실제 화면 해상도로 변환한다.
 */
@Serializable
data class RefPoint(
    val x: Int = 0,
    val y: Int = 0,
)

/** 기준 해상도 좌표계에서의 검색 영역. null 이면 화면 전체를 뜻한다. */
@Serializable
data class Roi(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isValid: Boolean get() = width > 0 && height > 0

    val right: Int get() = x + width
    val bottom: Int get() = y + height

    companion object {
        fun fromBounds(left: Int, top: Int, right: Int, bottom: Int): Roi {
            val l = minOf(left, right)
            val t = minOf(top, bottom)
            return Roi(l, t, maxOf(left, right) - l, maxOf(top, bottom) - t)
        }
    }
}
