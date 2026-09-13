package com.macromobile.inputmirror.model

import kotlinx.serialization.Serializable

/**
 * 화면 위의 사각 영역(실제 화면 픽셀).
 *
 * 테스트 모드에서는 앱 안의 테스트 영역을, 본 모드에서는 분할화면에 떠 있는 각 게임 창의
 * 영역을 담는다. 본 모드의 좌표는 **언제나 화면(디스플레이) 좌표**다.
 */
@Serializable
data class Region(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isValid: Boolean get() = width > 0 && height > 0

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /** 이 영역을 화면 안으로 가둔다. 화면 밖 좌표를 주입하면 엉뚱한 곳이 눌린다. */
    fun clampInto(screenWidth: Int, screenHeight: Int): Region = Region(
        left = left.coerceIn(0, screenWidth),
        top = top.coerceIn(0, screenHeight),
        right = right.coerceIn(0, screenWidth),
        bottom = bottom.coerceIn(0, screenHeight),
    )

    /** 다른 영역과 겹치는가. 영역이 서로 겹치면 주입이 엉뚱한 창으로 간다. */
    fun overlaps(other: Region): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** 최소 크기를 넘는가. 너무 작으면 좌표 변환의 의미가 없다. */
    fun isUsable(minSide: Int = MIN_SIDE): Boolean = width >= minSide && height >= minSide

    companion object {
        val EMPTY = Region(0, 0, 0, 0)

        /** 영역으로 인정하는 최소 한 변(픽셀). 이보다 작으면 지정 실수로 본다. */
        const val MIN_SIDE = 48

        /** 두 점으로 만든다. 어느 쪽이 먼저 찍혔든 정규화한다. */
        fun fromPoints(x1: Float, y1: Float, x2: Float, y2: Float) = Region(
            left = minOf(x1, x2).toInt(),
            top = minOf(y1, y2).toInt(),
            right = maxOf(x1, x2).toInt(),
            bottom = maxOf(y1, y2).toInt(),
        )

        fun of(left: Number, top: Number, width: Number, height: Number) = Region(
            left = left.toInt(),
            top = top.toInt(),
            right = left.toInt() + width.toInt(),
            bottom = top.toInt() + height.toInt(),
        )
    }
}

/** 창 비율이 서로 다를 때 좌표를 어떻게 맞출지. */
enum class FitMode {
    /**
     * 가로·세로를 각각 늘린다. 창 전체를 빠짐없이 덮지만 비율이 다르면 모양이 일그러진다.
     * 두 창의 비율이 같다면 [FIT] 과 결과가 같다.
     */
    STRETCH,

    /**
     * 가로·세로 중 작은 배율에 맞춰 비율을 지키고, 남는 부분은 여백(레터박스)으로 둔다.
     * 같은 게임을 크기만 다르게 띄운 경우 이쪽이 맞다.
     */
    FIT,
    ;

    val koreanLabel: String
        get() = when (this) {
            STRETCH -> "늘려 맞추기 (창 전체 사용)"
            FIT -> "비율 유지 (여백 생김)"
        }
}
