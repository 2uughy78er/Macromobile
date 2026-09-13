package com.macromobile.inputmirror.model

/**
 * 화면 위의 사각 영역(실제 화면 픽셀).
 *
 * MVP 에서는 앱 안의 테스트 영역을 가리키지만, 본 프로젝트에서는 분할화면에 떠 있는
 * 각 게임 창의 영역을 그대로 담게 된다. 그래서 처음부터 "창 영역"으로 다룬다.
 */
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

    companion object {
        val EMPTY = Region(0, 0, 0, 0)

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
