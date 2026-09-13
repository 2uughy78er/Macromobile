package com.macromobile.inputmirror.input

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.macromobile.inputmirror.diag.DiagLog
import com.macromobile.inputmirror.diag.DiagStage

/**
 * 좌표 문제를 진단하기 위한 환경 정보.
 *
 * 어긋남의 원인을 추측하지 않고 눈으로 확인하기 위해, 실제 값을 그대로 모아 보여준다.
 *
 * ## 여기서 크래시가 났던 이유
 *
 * 이전 판은 `EnvironmentInfo.collect(getApplication(), view)` 로 **Application 컨텍스트**를
 * 넘긴 뒤 그 컨텍스트에 대고 `Context.getDisplay()` 와
 * `WindowManager.getCurrentWindowMetrics()` 를 호출했다.
 *
 * `Context.getDisplay()` 는 **디스플레이에 연결된 컨텍스트**(Activity, `createWindowContext`,
 * `createDisplayContext` 로 만든 것)에서만 쓸 수 있고, 그 밖의 컨텍스트에서는
 * `UnsupportedOperationException` 을 던진다. Application 컨텍스트가 바로 그 "그 밖" 이다.
 * `getCurrentWindowMetrics()` 도 같은 이유로 UI 컨텍스트를 요구한다.
 *
 * 그래서 이제 컨텍스트를 밖에서 받지 않고 **View 에서 직접 얻는다.** View 의 컨텍스트는
 * 그 View 를 띄운 Activity 이므로 항상 디스플레이에 연결되어 있다. 회전 정보도
 * `Context.getDisplay()` 대신 `View.getDisplay()` 로 읽는다. 이쪽은 붙어 있지 않으면
 * 예외 대신 null 을 돌려주므로 안전하다.
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
    /** 어떤 종류의 컨텍스트로 읽었는지. 크래시 재발을 눈으로 막기 위해 표시한다. */
    val contextKind: String = "(알 수 없음)",
    /** 읽지 못한 항목이 있으면 그 이유. 조용히 넘어가지 않고 화면에 그대로 보여준다. */
    val notes: List<String> = emptyList(),
) {
    /** 사람이 읽을 수 있는 여러 줄 보고서. 화면과 로그 파일에 같은 내용을 쓴다. */
    fun report(): String = buildString {
        appendLine("[디스플레이] ${displayWidth} × ${displayHeight}  회전=$rotation")
        appendLine("[창]        원점 ($windowLeft, $windowTop)  크기 ${windowWidth} × ${windowHeight}")
        appendLine("[밀도]      density=$density  dpi=$densityDpi")
        appendLine("[인셋]      상태바=$statusBarInset  내비=$navigationBarInset")
        appendLine("[컷아웃]    top=$cutoutTopInset  left=$cutoutLeftInset")
        appendLine("[테스트뷰]  화면상 원점 ($viewOriginX, $viewOriginY)  크기 ${viewWidth} × ${viewHeight}")
        appendLine("[컨텍스트]  $contextKind")
        notes.forEach { appendLine("[주의]      $it") }
        append("→ View 좌표에 (+$viewOriginX, +$viewOriginY) 를 더해야 Screen 좌표가 된다.")
    }

    companion object {

        /**
         * 지금 이 순간의 실제 값을 모은다.
         *
         * 컨텍스트는 [view] 에서 가져온다. 밖에서 받지 않는 이유는 위 설명 그대로다 —
         * 잘못된 컨텍스트가 들어올 여지를 타입 차원에서 없앤다.
         */
        fun collect(view: View, readWindowMetrics: Boolean = true): EnvironmentInfo {
            val notes = ArrayList<String>()
            val context = view.context
            val activity = context.findActivity()
            if (activity == null) {
                notes += "View 의 컨텍스트에서 Activity 를 찾지 못했습니다. " +
                    "창 정보는 화면 메트릭으로 대체합니다."
            }

            val metrics: DisplayMetrics = DiagLog.runStage(
                DiagStage.DISPLAY_METRICS_READ,
                COMPONENT,
            ) { context.resources.displayMetrics }

            var windowLeft = 0
            var windowTop = 0
            var windowWidth = metrics.widthPixels
            var windowHeight = metrics.heightPixels
            var status = 0
            var nav = 0
            var cutoutTop = 0
            var cutoutLeft = 0

            // 창 메트릭은 반드시 UI 컨텍스트(Activity)로만 읽는다.
            val windowManager = if (readWindowMetrics) {
                activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            } else {
                null
            }
            if (!readWindowMetrics) {
                DiagLog.skip(
                    DiagStage.WINDOW_METRICS_READ,
                    COMPONENT,
                    "이 단계에서는 창 메트릭을 읽지 않습니다",
                )
            } else if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val read = DiagLog.tryStage(DiagStage.WINDOW_METRICS_READ, COMPONENT) {
                    windowManager.currentWindowMetrics
                }
                read.onSuccess { wmMetrics ->
                    val bounds: Rect = wmMetrics.bounds
                    windowLeft = bounds.left
                    windowTop = bounds.top
                    windowWidth = bounds.width()
                    windowHeight = bounds.height()

                    val insets = wmMetrics.windowInsets
                    status = insets.getInsets(WindowInsets.Type.statusBars()).top
                    nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    insets.displayCutout?.let {
                        cutoutTop = it.safeInsetTop
                        cutoutLeft = it.safeInsetLeft
                    }
                }
                read.onFailure {
                    notes += "창 메트릭을 읽지 못했습니다 (${it::class.java.simpleName}). " +
                        "화면 메트릭으로 대체합니다."
                }
            } else if (windowManager == null) {
                DiagLog.skip(
                    DiagStage.WINDOW_METRICS_READ,
                    COMPONENT,
                    "Activity 를 찾지 못해 건너뜀",
                )
            } else {
                DiagLog.skip(
                    DiagStage.WINDOW_METRICS_READ,
                    COMPONENT,
                    "API ${Build.VERSION.SDK_INT} — currentWindowMetrics 없음",
                )
            }

            val origin = IntArray(2)
            view.getLocationOnScreen(origin)

            // View.getDisplay() 는 붙어 있지 않으면 null 을 주고 예외를 던지지 않는다.
            val rotation = view.display?.rotation ?: run {
                notes += "View 가 아직 창에 붙지 않아 회전값을 읽지 못했습니다."
                0
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
                viewWidth = view.width,
                viewHeight = view.height,
                rotation = rotation,
                contextKind = activity?.let { "Activity (${it::class.java.simpleName})" }
                    ?: context::class.java.simpleName,
                notes = notes,
            )
        }

        private const val COMPONENT = "EnvironmentInfo"
    }
}

/** Activity 를 찾아 창 정보를 얻기 위한 도우미. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
