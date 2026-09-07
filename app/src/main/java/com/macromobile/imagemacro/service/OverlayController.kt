package com.macromobile.imagemacro.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.macromobile.imagemacro.automation.MacroStatus
import com.macromobile.imagemacro.automation.RunState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 실행 중 화면 위에 떠 있는 작은 컨트롤러.
 *
 * 다른 앱을 보고 있는 동안에도 매크로를 멈추거나 상태를 확인할 수 있어야 하므로
 * 시스템 오버레이로 띄운다. "다른 앱 위에 표시" 권한이 없으면 조용히 건너뛴다.
 */
class OverlayController(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var playPause: TextView? = null
    private var recordButton: TextView? = null

    /** 마지막으로 표시한 녹화 상태. 화면을 다시 띄울 때 그대로 복원한다. */
    private var recording = false
    private var recordedCount = 0

    var onPlayPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null

    /** 녹화 시작/중지 토글. */
    var onRecordToggle: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    fun show() {
        if (isShowing) return
        if (!canDrawOverlays(context)) {
            Log.i(TAG, "오버레이 권한이 없어 컨트롤러를 띄우지 않습니다.")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(230, 18, 26, 38))
                setStroke(dp(1), Color.argb(120, 120, 170, 255))
            }
        }

        val status = TextView(context).apply {
            text = "대기"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }
        val play = button("▶") { onPlayPause?.invoke() }
        val stop = button("■") { onStop?.invoke() }
        val record = button("●") { onRecordToggle?.invoke() }
        val open = button("🎯") { onOpenApp?.invoke() }

        container.addView(status)
        container.addView(play)
        container.addView(stop)
        container.addView(record)
        container.addView(open)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        makeDraggable(container, params, wm)

        try {
            wm.addView(container, params)
            root = container
            statusText = status
            playPause = play
            recordButton = record
            layoutParams = params
            applyRecordingLook()
        } catch (e: Exception) {
            Log.e(TAG, "오버레이를 띄우지 못했습니다", e)
            windowManager = null
        }
    }

    fun update(status: MacroStatus) {
        if (recording) return
        statusText?.text = when (status.state) {
            RunState.RUNNING -> "${status.progressLabel} · ${status.cycleLabel}"
            RunState.TARGET_FOUND -> "🎯 ${status.targetFound?.targetName ?: "발견"}"
            else -> status.state.koreanLabel
        }
        playPause?.text = if (status.state == RunState.RUNNING) "⏸" else "▶"
    }

    /** 녹화 중임을 컨트롤러에 표시한다. */
    fun setRecording(active: Boolean, count: Int) {
        recording = active
        recordedCount = count
        applyRecordingLook()
    }

    private fun applyRecordingLook() {
        recordButton?.setTextColor(if (recording) Color.rgb(255, 90, 90) else Color.WHITE)
        if (recording) {
            statusText?.text = "녹화 중 ${recordedCount}개"
        }
    }

    /**
     * 컨트롤러를 다시 붙여 맨 위로 올린다.
     *
     * 녹화용 전체 화면 오버레이를 띄우면 그게 위를 덮어 컨트롤러를 누를 수 없게 된다.
     * 같은 종류의 창은 나중에 붙인 쪽이 위로 오므로, 떼었다 다시 붙여 순서를 되돌린다.
     */
    fun bringToFront() {
        val view = root ?: return
        val lp = layoutParams ?: return
        val wm = windowManager ?: return
        runCatching {
            wm.removeView(view)
            wm.addView(view, lp)
        }.onFailure { Log.w(TAG, "컨트롤러를 위로 올리지 못했습니다", it) }
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager?.removeView(view) }
        root = null
        statusText = null
        playPause = null
        recordButton = null
        layoutParams = null
        windowManager = null
    }

    private fun button(label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val pad = (10 * density).roundToInt()
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { onClick() }
        }
    }

    /** 오버레이를 손가락으로 끌어 옮길 수 있게 한다. */
    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "OverlayController"
        private const val TOUCH_SLOP = 8f

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
