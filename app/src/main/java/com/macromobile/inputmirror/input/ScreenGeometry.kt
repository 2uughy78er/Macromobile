package com.macromobile.inputmirror.input

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
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

/**
 * 좌표 문제를 진단하기 위한 환경 정보.
 *
 * 어긋남의 원인을 추측하지 않고 눈으로 확인하기 위해, 실제 값을 그대로 모아 보여준다.
 */
data class EnvironmentInfo(
    val displayWidth: Int,
    val displayHeight: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val density: Float,
    val densityDpi: Int,
    val statusBarInset: Int,
    val navigationBarInset: Int,
    val cutoutTopInset: Int,
    val cutoutLeftInset: Int,
    val viewOriginX: Int,
    val viewOriginY: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val rotation: Int,
) {
    /** 사람이 읽을 수 있는 여러 줄 보고서. 화면과 로그 파일에 같은 내용을 쓴다. */
    fun report(): String = buildString {
        appendLine("[디스플레이] ${displayWidth} × ${displayHeight}  회전=$rotation")
        appendLine("[창]        원점 ($windowLeft, $windowTop)  크기 ${windowWidth} × ${windowHeight}")
        appendLine("[밀도]      density=$density  dpi=$densityDpi")
        appendLine("[인셋]      상태바=$statusBarInset  내비=$navigationBarInset")
        appendLine("[컷아웃]    top=$cutoutTopInset  left=$cutoutLeftInset")
        appendLine("[테스트뷰]  화면상 원점 ($viewOriginX, $viewOriginY)  크기 ${viewWidth} × ${viewHeight}")
        append("→ View 좌표에 (+$viewOriginX, +$viewOriginY) 를 더해야 Screen 좌표가 된다.")
    }

    companion object {
        /** 지금 이 순간의 실제 값을 모은다. */
        @Suppress("DEPRECATION")
        fun collect(context: Context, view: View?): EnvironmentInfo {
            val metrics = context.resources.displayMetrics
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

            var windowLeft = 0
            var windowTop = 0
            var windowWidth = metrics.widthPixels
            var windowHeight = metrics.heightPixels
            var status = 0
            var nav = 0
            var cutoutTop = 0
            var cutoutLeft = 0

            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wmMetrics = wm.currentWindowMetrics
                val bounds: Rect = wmMetrics.bounds
                windowLeft = bounds.left
                windowTop = bounds.top
                windowWidth = bounds.width()
                windowHeight = bounds.height()

                val insets = wmMetrics.windowInsets
                val bars = insets.getInsets(WindowInsets.Type.statusBars())
                val navBars = insets.getInsets(WindowInsets.Type.navigationBars())
                status = bars.top
                nav = navBars.bottom
                insets.displayCutout?.let {
                    cutoutTop = it.safeInsetTop
                    cutoutLeft = it.safeInsetLeft
                }
            }

            val origin = IntArray(2)
            view?.getLocationOnScreen(origin)

            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: 0
            } else {
                wm?.defaultDisplay?.rotation ?: 0
            }

            return EnvironmentInfo(
                displayWidth = maxOf(windowWidth, metrics.widthPixels),
                displayHeight = maxOf(windowHeight, metrics.heightPixels),
                windowLeft = windowLeft,
                windowTop = windowTop,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                density = metrics.density,
                densityDpi = metrics.densityDpi,
                statusBarInset = status,
                navigationBarInset = nav,
                cutoutTopInset = cutoutTop,
                cutoutLeftInset = cutoutLeft,
                viewOriginX = origin[0],
                viewOriginY = origin[1],
                viewWidth = view?.width ?: 0,
                viewHeight = view?.height ?: 0,
                rotation = rotation,
            )
        }
    }
}

/** Activity 를 찾아 창 정보를 얻기 위한 도우미. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
