package com.macromobile.inputmirror.ui.test

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.macromobile.inputmirror.input.MappedPoint
import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.input.TouchPoint
import com.macromobile.inputmirror.model.Region
import kotlin.math.roundToInt

/** 테스트 영역 하나. */
data class TestArea(val name: String, val isMaster: Boolean, var region: Region = Region.EMPTY)

/**
 * 검증용 테스트 화면.
 *
 * 화면을 넷으로 나눠 왼쪽 위를 MASTER, 나머지를 TARGET 1~3 으로 쓴다.
 * MASTER 영역에서 손가락을 움직이면 그 입력이 접근성 서비스를 통해 TARGET 영역으로
 * **실제로 주입된다.** TARGET 영역에 그려지는 선은 시늉이 아니라 시스템이 실제로
 * 전달한 터치를 받아 그린 것이다. 그래서 이 화면에 선이 그려진다는 것은 곧
 * "주입이 통했다"는 증거가 된다.
 *
 * 여러 손가락이 동시에 들어오므로 뷰에 맡기지 않고 [onTouchEvent] 에서 모든 포인터를
 * 직접 받아 좌표로 어느 영역인지 판단한다.
 */
class MirrorTestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** MASTER 영역의 터치를 바깥(전송기)으로 넘긴다. */
    var onMasterDown: ((TouchPoint) -> Unit)? = null
    var onMasterMove: ((List<TouchPoint>) -> Unit)? = null
    var onMasterUp: ((List<TouchPoint>) -> Unit)? = null
    var onMasterCancel: (() -> Unit)? = null

    /**
     * 영역이 정해지면 알려준다.
     *
     * 영역은 **View 공간**이고, [ScreenGeometry] 는 그걸 화면 공간으로 옮기는 변환이다.
     * 둘을 함께 넘겨야 받는 쪽이 어느 공간인지 헷갈리지 않는다.
     */
    var onAreasChanged: ((master: Region, targets: List<Region>, geometry: ScreenGeometry) -> Unit)? = null

    /** 각 대상에 주입하려는 화면 좌표. 눈으로 오차를 확인하기 위해 표시한다. */
    private val plannedScreenPoints = HashMap<String, MappedPoint>()

    /** 각 대상의 마지막 결과 문자열. COMPLETED / CANCELLED 등. */
    private val lastResults = HashMap<String, String>()

    /** 화면상 원점. 그릴 때 화면 좌표를 View 좌표로 되돌리는 데 쓴다. */
    private var geometry: ScreenGeometry = ScreenGeometry.IDENTITY

    val areas = listOf(
        TestArea("MASTER", isMaster = true),
        TestArea("TARGET 1", isMaster = false),
        TestArea("TARGET 2", isMaster = false),
        TestArea("TARGET 3", isMaster = false),
    )

    /** 영역별로 그려줄 경로. 화면에 남겨 눈으로 확인한다. */
    private val traces = HashMap<String, MutableList<MutableList<TouchPoint>>>()
    private val lastPoint = HashMap<String, TouchPoint>()

    /** MASTER 에서 진행 중인 경로. 아직 보내지 않은 점을 모은다. */
    private var masterPoints = ArrayList<TouchPoint>()
    private var masterPointerId = MotionEvent.INVALID_POINTER_ID
    private var pendingMoves = ArrayList<TouchPoint>()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 210, 220, 235)
        textSize = 26f
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plannedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutAreas(w, h)
    }

    /** 화면을 2×2 로 나눈다. 창 크기가 바뀌면 다시 계산한다(요구사항 15번). */
    private fun layoutAreas(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val halfW = w / 2
        val halfH = h / 2
        areas[0].region = Region.of(0, 0, halfW, halfH)
        areas[1].region = Region.of(halfW, 0, w - halfW, halfH)
        areas[2].region = Region.of(0, halfH, halfW, h - halfH)
        areas[3].region = Region.of(halfW, halfH, w - halfW, h - halfH)
        publishAreas()
    }

    /**
     * 영역과 화면 위치를 함께 알린다.
     *
     * 화면상 위치는 레이아웃이 끝나야 정확하므로 [onAttachedToWindow] 와 레이아웃 이후에도
     * 다시 알린다. 여기서 한 번 틀리면 주입 좌표가 통째로 어긋난다.
     */
    private fun publishAreas() {
        if (width <= 0 || height <= 0) return
        geometry = ScreenGeometry.of(this)
        onAreasChanged?.invoke(areas[0].region, areas.drop(1).map { it.region }, geometry)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 창이 움직이거나 인셋이 바뀌면 화면상 원점도 바뀐다. 매번 다시 읽는다.
        publishAreas()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { publishAreas() }
    }

    /** 전송기가 계산한 주입 좌표(화면 공간)를 받아 표시한다. */
    fun showPlannedPoints(points: Map<String, MappedPoint>) {
        plannedScreenPoints.clear()
        plannedScreenPoints.putAll(points)
        invalidate()
    }

    /** 대상별 마지막 결과를 받아 표시한다. */
    fun showResults(results: Map<String, String>) {
        lastResults.clear()
        lastResults.putAll(results)
        invalidate()
    }

    fun clearTraces() {
        traces.clear()
        lastPoint.clear()
        plannedScreenPoints.clear()
        lastResults.clear()
        invalidate()
    }

    /** 지금 화면상 원점. 바깥에서 환경 정보를 찍을 때 쓴다. */
    fun currentGeometry(): ScreenGeometry = geometry

    // ------------------------------------------------------------------
    // 터치 처리
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handleDown(event.getPointerId(index), event.getX(index), event.getY(index), now)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    handleMove(event.getPointerId(index), event.getX(index), event.getY(index), now)
                }
                flushMasterMoves()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                handleUp(event.getPointerId(index), event.getX(index), event.getY(index), now)
            }

            MotionEvent.ACTION_CANCEL -> {
                if (masterPointerId != MotionEvent.INVALID_POINTER_ID) {
                    masterPointerId = MotionEvent.INVALID_POINTER_ID
                    masterPoints = ArrayList()
                    pendingMoves.clear()
                    onMasterCancel?.invoke()
                }
            }
        }
        invalidate()
        return true
    }

    private fun handleDown(pointerId: Int, x: Float, y: Float, now: Long) {
        val area = areaAt(x, y) ?: return
        val point = TouchPoint(x, y, now)
        startTrace(area.name, point)

        if (area.isMaster && masterPointerId == MotionEvent.INVALID_POINTER_ID) {
            // 미러링은 손가락 하나만 따라간다. 여러 손가락은 기록만 하고 보내지 않는다.
            masterPointerId = pointerId
            masterPoints = arrayListOf(point)
            pendingMoves.clear()
            onMasterDown?.invoke(point)
        }
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float, now: Long) {
        val area = areaAt(x, y) ?: return
        val point = TouchPoint(x, y, now)
        appendTrace(area.name, point)
        if (pointerId == masterPointerId) {
            masterPoints.add(point)
            pendingMoves.add(point)
        }
    }

    private fun flushMasterMoves() {
        if (pendingMoves.isEmpty()) return
        val batch = ArrayList(pendingMoves)
        pendingMoves.clear()
        onMasterMove?.invoke(batch)
    }

    private fun handleUp(pointerId: Int, x: Float, y: Float, now: Long) {
        val area = areaAt(x, y)
        val point = TouchPoint(x, y, now)
        if (area != null) appendTrace(area.name, point)

        if (pointerId == masterPointerId) {
            masterPoints.add(point)
            val tail = ArrayList(pendingMoves).apply { add(point) }
            pendingMoves.clear()
            onMasterUp?.invoke(tail)
            masterPointerId = MotionEvent.INVALID_POINTER_ID
        }
    }

    /** MASTER 경로 전체. 일괄 전송 방식에서 쓴다. */
    fun currentMasterPath(): List<TouchPoint> = ArrayList(masterPoints)

    private fun areaAt(x: Float, y: Float): TestArea? =
        areas.firstOrNull { it.region.isValid && it.region.contains(x, y) }

    private fun startTrace(areaName: String, point: TouchPoint) {
        val list = traces.getOrPut(areaName) { ArrayList() }
        list += arrayListOf(point)
        while (list.size > MAX_TRACES_PER_AREA) list.removeAt(0)
        lastPoint[areaName] = point
    }

    private fun appendTrace(areaName: String, point: TouchPoint) {
        val list = traces[areaName] ?: return
        list.lastOrNull()?.add(point)
        lastPoint[areaName] = point
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        areas.forEach { area ->
            if (!area.region.isValid) return@forEach
            drawArea(canvas, area)
        }
    }

    private fun drawArea(canvas: Canvas, area: TestArea) {
        val r = area.region
        val accent = if (area.isMaster) MASTER_COLOR else TARGET_COLOR

        fillPaint.color = if (area.isMaster) MASTER_BG else TARGET_BG
        canvas.drawRect(
            r.left.toFloat() + 4, r.top.toFloat() + 4,
            r.right.toFloat() - 4, r.bottom.toFloat() - 4,
            fillPaint,
        )
        borderPaint.color = accent
        canvas.drawRect(
            r.left.toFloat() + 4, r.top.toFloat() + 4,
            r.right.toFloat() - 4, r.bottom.toFloat() - 4,
            borderPaint,
        )

        canvas.drawText(area.name, r.left + 22f, r.top + 48f, labelPaint)
        canvas.drawText("${r.width} × ${r.height}", r.left + 22f, r.top + 82f, infoPaint)

        lastPoint[area.name]?.let { p ->
            val localX = (p.x - r.left).roundToInt()
            val localY = (p.y - r.top).roundToInt()
            canvas.drawText(
                "view (${p.x.roundToInt()}, ${p.y.roundToInt()}) → " +
                    "screen (${geometry.toScreenX(p.x).roundToInt()}, " +
                    "${geometry.toScreenY(p.y).roundToInt()})",
                r.left + 22f, r.bottom - 58f, infoPaint,
            )
            canvas.drawText("영역 안 ($localX, $localY)", r.left + 22f, r.bottom - 26f, infoPaint)
        }

        lastResults[area.name]?.let { result ->
            infoPaint.color = if (result.startsWith("COMPLETED")) OK_COLOR else FAIL_COLOR
            canvas.drawText(result, r.left + 22f, r.top + 116f, infoPaint)
            infoPaint.color = INFO_COLOR
        }

        // 주입하려는 위치를 다른 모양으로 표시한다. 실제 그려진 선과 어긋나면 좌표 문제다.
        plannedScreenPoints[area.name]?.let { planned ->
            val vx = geometry.toViewX(planned.x)
            val vy = geometry.toViewY(planned.y)
            plannedPaint.color = PLANNED_COLOR
            canvas.drawLine(vx - 26f, vy, vx + 26f, vy, plannedPaint)
            canvas.drawLine(vx, vy - 26f, vx, vy + 26f, plannedPaint)
            canvas.drawCircle(vx, vy, 20f, plannedPaint)
            infoPaint.color = PLANNED_COLOR
            canvas.drawText(
                "주입 예정 (${planned.x.toInt()}, ${planned.y.toInt()})",
                r.left + 22f, r.bottom - 90f, infoPaint,
            )
            infoPaint.color = INFO_COLOR
        }

        tracePaint.color = accent
        dotPaint.color = accent
        traces[area.name]?.forEach { stroke ->
            if (stroke.size == 1) {
                canvas.drawCircle(stroke[0].x, stroke[0].y, 14f, dotPaint)
            } else if (stroke.size > 1) {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                canvas.drawPath(path, tracePaint)
                canvas.drawCircle(stroke.first().x, stroke.first().y, 12f, dotPaint)
            }
        }
    }

    private companion object {
        const val MAX_TRACES_PER_AREA = 6
        val MASTER_COLOR = Color.rgb(90, 170, 255)
        val TARGET_COLOR = Color.rgb(110, 220, 140)
        val MASTER_BG = Color.rgb(24, 34, 52)
        val TARGET_BG = Color.rgb(22, 40, 32)

        /** 주입하려는 위치. 실제 전달된 터치(초록)와 겹치는지 눈으로 비교한다. */
        val PLANNED_COLOR = Color.rgb(255, 190, 70)
        val OK_COLOR = Color.rgb(120, 230, 150)
        val FAIL_COLOR = Color.rgb(255, 120, 120)
        val INFO_COLOR = Color.argb(220, 210, 220, 235)
    }
}
