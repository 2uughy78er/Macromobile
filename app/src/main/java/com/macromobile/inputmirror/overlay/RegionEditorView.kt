package com.macromobile.inputmirror.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.macromobile.inputmirror.model.MirrorRegion
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.service.WindowSnapshot

/**
 * 화면 위에서 영역을 직접 그려 지정하는 오버레이.
 *
 * ## 좌표가 정확한 이유
 *
 * 이 뷰는 [OverlayController.fullScreenParams] 로 띄운 전체 화면 오버레이에 들어간다.
 * 그 창은 인셋에 맞춰 밀리지 않으므로 **뷰의 (0,0) 이 디스플레이의 (0,0) 과 같다.**
 * 따라서 `MotionEvent.getX()/getY()` 가 곧 화면 좌표이고, 여기서 만든 [Region] 을
 * `dispatchGesture` 에 그대로 쓸 수 있다. 상태바 높이를 더하거나 빼는 보정이 없다.
 *
 * ## 쓰는 법
 *
 * 손가락으로 사각형을 그리면 그 영역이 된다. 화면에 이미 떠 있는 앱 창을 그대로 쓰고
 * 싶으면 그 창의 점선 테두리를 누르면 값이 채워진다(창 정보는 시스템에서 읽은 실제 값).
 */
class RegionEditorView(
    context: Context,
    /** 지금 지정 중인 영역의 이름. 화면에 그대로 보여준다. */
    private val editingName: String,
    /** 이미 지정된 다른 영역들. 겹치지 않게 참고용으로 그린다. */
    private val existing: List<MirrorRegion>,
    /** 시스템이 알려준 실제 창들. 눌러서 그대로 가져올 수 있다. */
    private val candidates: List<WindowSnapshot>,
    /** 처음 값(고치는 경우). 없으면 빈 상태로 시작. */
    initial: Region? = null,
    private val onConfirm: (Region) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var current: Region? = initial
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false

    /** 버튼 자리. onLayout 에서 실제 크기에 맞춰 다시 계산한다. */
    private var confirmRect = RectF()
    private var cancelRect = RectF()

    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val clearPaint = Paint().apply { color = Color.argb(40, 90, 170, 255) }
    private val currentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.rgb(90, 170, 255)
    }
    private val existingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(180, 110, 220, 140)
    }
    private val candidateStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(140, 255, 190, 70)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(18f)
        isFakeBoldText = true
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 220, 230, 245)
        textSize = dp(13f)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val buttonW = dp(120f)
        val buttonH = dp(46f)
        val gap = dp(16f)
        val y = height - buttonH - dp(28f)
        val centerX = width / 2f
        cancelRect = RectF(centerX - buttonW - gap / 2, y, centerX - gap / 2, y + buttonH)
        confirmRect = RectF(centerX + gap / 2, y, centerX + buttonW + gap / 2, y + buttonH)
    }

    // ------------------------------------------------------------------
    // 터치
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (confirmRect.contains(x, y) || cancelRect.contains(x, y)) return true

                // 시스템이 알려준 창 테두리를 누르면 그 값을 그대로 가져온다.
                val tapped = candidates.firstOrNull { it.bounds.contains(x, y) }
                if (tapped != null && current?.contains(x, y) != true) {
                    current = tapped.bounds
                    invalidate()
                    return true
                }

                dragStartX = x
                dragStartY = y
                dragging = true
                current = null
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    current = Region.fromPoints(dragStartX, dragStartY, x, y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    when {
                        confirmRect.contains(x, y) -> current
                            ?.takeIf { it.isUsable() }
                            ?.let(onConfirm)

                        cancelRect.contains(x, y) -> onCancel()
                    }
                    return true
                }
                dragging = false
                current = Region.fromPoints(dragStartX, dragStartY, x, y)
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        candidates.forEach { window ->
            drawRegion(canvas, window.bounds, candidateStroke)
            canvas.drawText(
                window.packageName ?: window.typeLabel,
                window.bounds.left + dp(8f),
                window.bounds.top + dp(20f),
                infoPaint,
            )
        }

        existing.forEach { region ->
            drawRegion(canvas, region.bounds, existingStroke)
            canvas.drawText(
                region.name,
                region.bounds.left + dp(8f),
                region.bounds.bottom - dp(10f),
                infoPaint,
            )
        }

        current?.let { region ->
            canvas.drawRect(
                region.left.toFloat(), region.top.toFloat(),
                region.right.toFloat(), region.bottom.toFloat(),
                clearPaint,
            )
            drawRegion(canvas, region, currentStroke)
        }

        drawHeader(canvas)
        drawButtons(canvas)
    }

    private fun drawRegion(canvas: Canvas, region: Region, paint: Paint) {
        canvas.drawRect(
            region.left.toFloat(), region.top.toFloat(),
            region.right.toFloat(), region.bottom.toFloat(),
            paint,
        )
    }

    private fun drawHeader(canvas: Canvas) {
        val x = dp(20f)
        var y = dp(52f)
        canvas.drawText("$editingName 영역 지정", x, y, titlePaint)

        y += dp(24f)
        canvas.drawText(
            "손가락으로 사각형을 그리세요. 점선은 시스템이 알려준 실제 창이며, 눌러서 그대로 쓸 수 있습니다.",
            x, y, infoPaint,
        )

        val region = current
        y += dp(22f)
        if (region == null) {
            canvas.drawText("아직 지정되지 않았습니다.", x, y, infoPaint)
            return
        }
        canvas.drawText(
            "L${region.left}  T${region.top}  R${region.right}  B${region.bottom}" +
                "   (${region.width} × ${region.height})",
            x, y, infoPaint,
        )
        if (!region.isUsable()) {
            y += dp(20f)
            infoPaint.color = Color.rgb(255, 140, 140)
            canvas.drawText(
                "너무 작습니다. 한 변이 ${Region.MIN_SIDE}px 이상이어야 합니다.",
                x, y, infoPaint,
            )
            infoPaint.color = Color.argb(230, 220, 230, 245)
            return
        }
        val clash = existing.firstOrNull { it.bounds.overlaps(region) }
        if (clash != null) {
            y += dp(20f)
            infoPaint.color = Color.rgb(255, 190, 70)
            canvas.drawText("${clash.name} 영역과 겹칩니다.", x, y, infoPaint)
            infoPaint.color = Color.argb(230, 220, 230, 245)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        val ready = current?.isUsable() == true
        val radius = dp(10f)

        buttonPaint.color = Color.argb(230, 60, 64, 72)
        canvas.drawRoundRect(cancelRect, radius, radius, buttonPaint)
        canvas.drawText(
            "취소",
            cancelRect.centerX(),
            cancelRect.centerY() + dp(5f),
            buttonTextPaint,
        )

        buttonPaint.color = if (ready) {
            Color.argb(240, 60, 130, 240)
        } else {
            Color.argb(150, 70, 80, 96)
        }
        canvas.drawRoundRect(confirmRect, radius, radius, buttonPaint)
        canvas.drawText(
            "확인",
            confirmRect.centerX(),
            confirmRect.centerY() + dp(5f),
            buttonTextPaint,
        )
    }
}
