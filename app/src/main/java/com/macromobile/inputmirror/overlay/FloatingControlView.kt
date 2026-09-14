package com.macromobile.inputmirror.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.macromobile.inputmirror.mirror.MirrorState
import kotlin.math.abs

/**
 * 게임 위에 떠 있는 작은 조작 버튼.
 *
 * 화면을 덮지 않는다. 필요한 것만 담는다 — 지금 상태, 일시정지/재개, 정지.
 * **STOP 은 어떤 상태에서도 눌린다.** 미러링이 이상하게 돌아갈 때 사용자가 즉시
 * 멈출 수 있어야 하기 때문이다.
 *
 * 손가락으로 끌어 옮길 수 있다. 게임의 중요한 부분을 가리면 치울 수 있어야 한다.
 */
class FloatingControlView(
    context: Context,
    /** 멈춰 있을 때 누르면 시작한다. 앱을 열지 않고도 시작할 수 있어야 한다. */
    private val onStart: () -> Unit,
    private val onPauseOrResume: () -> Unit,
    private val onStop: () -> Unit,
    /** 끌어서 옮길 때 창 위치를 바꿔달라고 알린다. */
    private val onMoved: (dx: Int, dy: Int) -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    var state: MirrorState = MirrorState.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 18, 24, 36)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 235, 242, 255)
        textSize = dp(13f)
        isFakeBoldText = true
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private var pauseRect = RectF()
    private var stopRect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(248f).toInt(), dp(48f).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val buttonW = dp(60f)
        val inset = dp(5f)
        stopRect = RectF(width - buttonW - inset, inset, width - inset, height - inset)
        pauseRect = RectF(
            stopRect.left - buttonW - dp(4f), inset, stopRect.left - dp(4f), height - inset,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragged = true
                if (dragged) {
                    onMoved(dx.toInt(), dy.toInt())
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    when {
                        stopRect.contains(event.x, event.y) -> onStop()
                        pauseRect.contains(event.x, event.y) -> when (state) {
                            MirrorState.RUNNING, MirrorState.PAUSED -> onPauseOrResume()
                            else -> onStart()
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private val touchSlop = dp(6f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(14f)
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backPaint,
        )

        dotPaint.color = when (state) {
            MirrorState.RUNNING -> Color.rgb(110, 220, 140)
            MirrorState.PAUSED -> Color.rgb(255, 190, 70)
            else -> Color.rgb(150, 158, 172)
        }
        canvas.drawCircle(dp(18f), height / 2f, dp(5f), dotPaint)
        canvas.drawText(state.koreanLabel, dp(30f), height / 2f + dp(5f), labelPaint)

        val buttonRadius = dp(10f)
        // 멈춰 있을 때는 이 버튼이 START 가 된다. 앱을 전체화면으로 띄우는 순간
        // 게임들이 뒤로 밀려 메모리 부족으로 죽을 수 있으므로, 앱을 열지 않고
        // 여기서 바로 시작할 수 있어야 한다.
        val primaryLabel = when (state) {
            MirrorState.RUNNING -> "❚❚ 정지"
            MirrorState.PAUSED -> "▶ 재개"
            else -> "▶ START"
        }
        buttonPaint.color = when (state) {
            MirrorState.RUNNING, MirrorState.PAUSED -> Color.argb(235, 60, 66, 80)
            else -> Color.argb(240, 60, 130, 240)
        }
        canvas.drawRoundRect(pauseRect, buttonRadius, buttonRadius, buttonPaint)
        canvas.drawText(
            primaryLabel,
            pauseRect.centerX(), pauseRect.centerY() + dp(4.5f), buttonTextPaint,
        )

        // STOP 은 언제나 눈에 띄어야 한다. 비상 정지이기 때문이다.
        buttonPaint.color = Color.argb(240, 190, 60, 70)
        canvas.drawRoundRect(stopRect, buttonRadius, buttonRadius, buttonPaint)
        canvas.drawText(
            "■ STOP", stopRect.centerX(), stopRect.centerY() + dp(4.5f), buttonTextPaint,
        )
    }
}
