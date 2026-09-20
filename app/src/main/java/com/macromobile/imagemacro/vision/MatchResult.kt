package com.macromobile.imagemacro.vision

/**
 * 템플릿 매칭 결과. 좌표는 모두 **실제 화면 픽셀** 기준이다.
 *
 * @param score 유사도(0.0 ~ 1.0)
 * @param centerX 매칭 영역 중심 X
 * @param centerY 매칭 영역 중심 Y
 * @param width 매칭에 쓰인 템플릿의 (보정된) 너비
 * @param height 매칭에 쓰인 템플릿의 (보정된) 높이
 */
data class MatchResult(
    val found: Boolean,
    val score: Float,
    val centerX: Int = 0,
    val centerY: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val left: Int get() = centerX - width / 2
    val top: Int get() = centerY - height / 2

    companion object {
        val NONE = MatchResult(found = false, score = 0f)
    }
}

/** 어떤 템플릿이 매칭됐는지까지 담은 결과. */
data class NamedMatch(
    val templateId: String,
    val templateName: String,
    val match: MatchResult,
)
