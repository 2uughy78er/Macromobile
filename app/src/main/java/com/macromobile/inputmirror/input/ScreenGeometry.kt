package com.macromobile.inputmirror.input

import android.view.View
import com.macromobile.inputmirror.model.Region

/**
 * 좌표계를 명시적으로 구분한다.
 *
 * 이 앱에서 좌표는 최소 세 가지 공간에 존재하며 **서로 같은 것이 아니다.**
 *
 * | 공간 | 원점 | 어디서 나오나 |
 * |---|---|---|
 * | View  | 그 View 의 좌상단 | `MotionEvent.getX()/getY()`, `View.getWidth()/getHeight()` |
 * | Window| 앱 창의 좌상단    | View 위치 + 부모들의 위치 |
 * | Screen| 디스플레이 좌상단  | `View.getLocationOnScreen()`, `MotionEvent.getRawX()/getRawY()` |
 *
 * **`dispatchGesture` 는 Screen 좌표를 요구한다.** View 좌표를 그대로 넘기면 View 가
 * 화면 원점에 있지 않은 만큼(상태바 높이 등) 어긋난 곳에 주입된다. 그 어긋남이 화면
 * 밖이나 다른 창(상태바)으로 넘어가면 입력이 아예 전달되지 않는다.
 *
 * 그래서 이 클래스는 두 공간을 **한 곳에서만** 오가게 하고, 나머지 코드는 자기가 어느
 * 공간의 좌표를 다루는지 이름으로 분명히 알 수 있게 한다.
 */
class ScreenGeometry(
    /** 테스트 View 의 좌상단이 화면에서 갖는 위치. */
    val viewOriginOnScreenX: Int,
    val viewOriginOnScreenY: Int,
) {
    /** View 좌표 → Screen 좌표. */
    fun toScreenX(viewX: Float): Float = viewX + viewOriginOnScreenX
    fun toScreenY(viewY: Float): Float = viewY + viewOriginOnScreenY

    /** Screen 좌표 → View 좌표. */
    fun toViewX(screenX: Float): Float = screenX - viewOriginOnScreenX
    fun toViewY(screenY: Float): Float = screenY - viewOriginOnScreenY

    /** View 공간의 영역을 Screen 공간으로 옮긴다. */
    fun toScreen(region: Region): Region = Region(
        left = region.left + viewOriginOnScreenX,
        top = region.top + viewOriginOnScreenY,
        right = region.right + viewOriginOnScreenX,
        bottom = region.bottom + viewOriginOnScreenY,
    )

    val isIdentity: Boolean get() = viewOriginOnScreenX == 0 && viewOriginOnScreenY == 0

    override fun toString() = "ScreenGeometry(origin=$viewOriginOnScreenX,$viewOriginOnScreenY)"

    companion object {
        val IDENTITY = ScreenGeometry(0, 0)

        /** View 가 화면 어디에 놓였는지 실제로 물어본다. 추측하지 않는다. */
        fun of(view: View): ScreenGeometry {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return ScreenGeometry(location[0], location[1])
        }
    }
}
