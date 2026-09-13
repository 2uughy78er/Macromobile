package com.macromobile.inputmirror.input

import com.macromobile.inputmirror.model.FitMode
import com.macromobile.inputmirror.model.Region
import kotlin.math.min

/** 변환된 좌표(실제 화면 픽셀). */
data class MappedPoint(val x: Float, val y: Float)

/**
 * 마스터 창의 좌표를 대상 창의 좌표로 옮긴다.
 *
 * 절대 좌표를 그대로 복사하지 않는다. 마스터 창 안에서의 **상대 위치**(0~1)를 구한 뒤
 * 대상 창 크기에 맞춰 되돌린다. 그래서 두 창의 위치와 크기가 달라도 "같은 곳"을 누른다.
 *
 * ```
 * 상대위치 = (터치 - 마스터창.좌상단) / 마스터창.크기
 * 대상좌표 = 대상창.좌상단 + 상대위치 × 대상창.크기
 * ```
 *
 * 두 창의 **비율이 다를 때** 어떻게 할지는 [FitMode] 로 정한다.
 * [FitMode.FIT] 은 비율을 지키고 남는 쪽을 가운데 정렬 여백으로 처리한다.
 *
 * 사용자가 직접 미세 조정할 수 있도록 배율과 이동값을 추가로 받는다(요구사항 6번).
 */
class CoordinateTransformer(
    private val master: Region,
    private val target: Region,
    private val fitMode: FitMode = FitMode.FIT,
    /** 사용자 수동 보정. 1.0 / 0 이면 보정 없음. */
    private val scaleX: Float = 1f,
    private val scaleY: Float = 1f,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f,
) {
    val isUsable: Boolean get() = master.isValid && target.isValid

    /** 마스터 안에서의 상대 위치(0~1). 창 밖이면 0~1 을 벗어난 값이 나온다. */
    fun relative(x: Float, y: Float): MappedPoint {
        if (!master.isValid) return MappedPoint(0f, 0f)
        return MappedPoint(
            (x - master.left) / master.width,
            (y - master.top) / master.height,
        )
    }

    /**
     * 마스터 화면 좌표를 대상 화면 좌표로 바꾼다.
     *
     * 변환할 수 없는 상태(창 크기를 모름)면 null 을 돌려준다. 이 경우 입력을 보내지 않는다.
     */
    fun map(x: Float, y: Float): MappedPoint? {
        if (!isUsable) return null
        val rel = relative(x, y)

        // 상대 위치를 대상 창에서 몇 픽셀로 펼칠지, 그리고 남는 여백이 얼마인지.
        val spanX: Float
        val spanY: Float
        val padX: Float
        val padY: Float
        when (fitMode) {
            FitMode.STRETCH -> {
                spanX = target.width.toFloat()
                spanY = target.height.toFloat()
                padX = 0f
                padY = 0f
            }

            FitMode.FIT -> {
                // 비율을 지키려면 가로·세로 중 작은 배율을 함께 쓰고, 남는 쪽을 가운데로 민다.
                val scale = min(
                    target.width.toFloat() / master.width,
                    target.height.toFloat() / master.height,
                )
                spanX = master.width * scale
                spanY = master.height * scale
                padX = (target.width - spanX) / 2f
                padY = (target.height - spanY) / 2f
            }
        }

        val mappedX = target.left + padX + rel.x * spanX
        val mappedY = target.top + padY + rel.y * spanY

        // 사용자 수동 보정은 대상 창 중심을 기준으로 적용한다.
        val adjustedX = target.centerX + (mappedX - target.centerX) * scaleX + offsetX
        val adjustedY = target.centerY + (mappedY - target.centerY) * scaleY + offsetY
        return MappedPoint(adjustedX, adjustedY)
    }

    /**
     * 변환된 좌표를 대상 창 안으로 가둔다.
     *
     * 창 밖으로 나간 좌표를 그대로 보내면 엉뚱한 앱이 터치를 받는다. 미러링에서는
     * 그게 가장 위험하므로 항상 잘라서 보낸다.
     */
    fun mapClamped(x: Float, y: Float): MappedPoint? {
        val p = map(x, y) ?: return null
        return MappedPoint(
            p.x.coerceIn(target.left.toFloat(), (target.right - 1).toFloat()),
            p.y.coerceIn(target.top.toFloat(), (target.bottom - 1).toFloat()),
        )
    }

    /** 변환 결과가 대상 창 안에 들어오는지. 밖이면 보내지 않는 편이 안전하다. */
    fun isInsideTarget(x: Float, y: Float): Boolean {
        val p = map(x, y) ?: return false
        return target.contains(p.x, p.y)
    }
}
