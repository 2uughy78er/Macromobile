package com.macromobile.inputmirror.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.input.TouchPoint

/**
 * MASTER 영역 위에 덮여 사용자의 입력을 받는 오버레이.
 *
 * ## 왜 게임을 직접 만지지 않고 이 위를 만지는가
 *
 * 접근성 서비스는 다른 앱 창에서 일어나는 **원본 터치 좌표를 볼 수 없다.** 그리고
 * `dispatchGesture` 는 문서상 "진행 중인 제스처는 사용자 것이든 무엇이든 취소한다".
 * 그래서 손가락을 게임에 얹은 채 주입하면 그 손가락이 끊긴다. 두 사실을 합치면,
 * 사용자의 입력을 우리가 먼저 받아서 **제스처가 끝난 뒤** 모든 대상에 재생하는 길밖에
 * 없다. MASTER 게임도 그 대상 중 하나가 된다.
 *
 * ## 좌표
 *
 * 이 뷰는 화면 좌표 (left, top) 에 정확히 놓인 오버레이 창 안에 있다. 그래도 위치를
 * 짐작하지 않고 [ScreenGeometry.of] 로 **실제 화면상 원점을 물어본다.** 테스트 화면이
 * 쓰는 것과 똑같은 코드이고, 창이 조금이라도 밀려 있으면 그 사실이 그대로 반영된다.
 */
class MasterInputView(
    context: Context,
    private val onDown: (TouchPoint) -> Unit,
    private val onMove: (List<TouchPoint>) -> Unit,
    private val onUp: (TouchPoint) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density

    /** 화면상 원점. 레이아웃이 끝날 때마다 다시 읽는다. */
    private var geometry: ScreenGeometry = ScreenGeometry.IDENTITY

    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private val pendingMoves = ArrayList<TouchPoint>()

    /** 방금 그린 경로(뷰 좌표). 사용자가 자기 동작을 눈으로 확인할 수 있게 그린다. */
    private val trail = ArrayList<TouchPoint>()

    /** 진행 중인 판정 상태 문구. 바깥(엔진)이 채워준다. */
    var statusText: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 이 오버레이가 입력을 받는 중인지. 꺼져 있으면 흐리게 그린다. */
    var active: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(200, 90, 170, 255)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(220, 120, 220, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 120, 220, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 235, 242, 255)
        textSize = 13f * density
    }
    private val labelBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 12, 18, 28)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        geometry = ScreenGeometry.of(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { geometry = ScreenGeometry.of(this) }
    }

    /** 지금 이 뷰가 화면 어디에 놓였는지. 엔진이 좌표 확인에 쓴다. */
    fun currentGeometry(): ScreenGeometry = geometry

    // ------------------------------------------------------------------
    // 터치 → 화면 좌표
    // ------------------------------------------------------------------

    private fun toScreen(x: Float, y: Float, now: Long) =
        TouchPoint(geometry.toScreenX(x), geometry.toScreenY(y), now)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val now = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                pendingMoves.clear()
                trail.clear()
                trail += TouchPoint(event.x, event.y, now)
                onDown(toScreen(event.x, event.y, now))
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    trail += TouchPoint(x, y, now)
                    pendingMoves += toScreen(x, y, now)
                    flush()
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    trail += TouchPoint(x, y, now)
                    flush()
                    onUp(toScreen(x, y, now))
                }
                pointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerId = MotionEvent.INVALID_POINTER_ID
                pendingMoves.clear()
                onCancel()
                invalidate()
            }
        }
        return true
    }

    private fun flush() {
        if (pendingMoves.isEmpty()) return
        val batch = ArrayList(pendingMoves)
        pendingMoves.clear()
        onMove(batch)
    }

    /** 방금 동작의 흔적을 지운다. 다음 제스처 전에 엔진이 부른다. */
    fun clearTrail() {
        trail.clear()
        invalidate()
    }

    // ------------------------------------------------------------------
    // 그리기 — 얇은 테두리와 상태 한 줄뿐. 게임을 가리지 않는다.
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = borderPaint.strokeWidth / 2f
        borderPaint.alpha = if (active) 200 else 70
        canvas.drawRect(
            inset, inset, width - inset, height - inset, borderPaint,
        )

        if (trail.size > 1) {
            val path = Path().apply {
                moveTo(trail[0].x, trail[0].y)
                for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
            }
            canvas.drawPath(path, trailPaint)
        }
        trail.firstOrNull()?.let { canvas.drawCircle(it.x, it.y, 9f * density, dotPaint) }

        statusText?.let { text ->
            val pad = 6f * density
            val textWidth = labelPaint.measureText(text)
            val boxTop = 6f * density
            val boxHeight = 22f * density
            canvas.drawRect(
                6f * density, boxTop,
                6f * density + textWidth + pad * 2, boxTop + boxHeight,
                labelBackPaint,
            )
            canvas.drawText(
                text,
                6f * density + pad,
                boxTop + boxHeight - 7f * density,
                labelPaint,
            )
        }
    }
}
