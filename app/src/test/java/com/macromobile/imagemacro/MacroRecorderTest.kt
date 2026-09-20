package com.macromobile.imagemacro

import com.macromobile.imagemacro.automation.MacroRecorder
import com.macromobile.imagemacro.automation.RecordedGesture
import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.vision.CoordinateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRecorderTest {

    /** 같은 해상도라 좌표가 그대로 남는 변환기. */
    private val sameSize = CoordinateMapper(1080, 2400, 1080, 2400)
    private val density = 1f

    private fun tap(x: Int, y: Int, at: Long, holdMs: Long = 50L) = RecordedGesture(
        startX = x, startY = y, endX = x, endY = y,
        startedAt = at, endedAt = at + holdMs, maxDistancePx = 2f,
    )

    @Test
    fun `제자리에서 끝난 동작은 좌표 터치가 된다`() {
        val steps = MacroRecorder.toSteps(listOf(tap(100, 200, 0)), sameSize, density)
        assertEquals(1, steps.size)
        assertEquals(ActionType.TAP, steps[0].type)
        assertEquals(100, steps[0].point?.x)
        assertEquals(200, steps[0].point?.y)
        assertEquals(0L, steps[0].tapHoldMs)
    }

    @Test
    fun `오래 붙잡고 있으면 길게 누르기로 기록된다`() {
        val steps = MacroRecorder.toSteps(listOf(tap(10, 20, 0, holdMs = 900)), sameSize, density)
        assertEquals(ActionType.TAP, steps[0].type)
        assertEquals(900L, steps[0].tapHoldMs)
    }

    @Test
    fun `멀리 끌면 스와이프가 된다`() {
        val drag = RecordedGesture(
            startX = 100, startY = 900, endX = 100, endY = 200,
            startedAt = 0, endedAt = 350, maxDistancePx = 700f,
        )
        val steps = MacroRecorder.toSteps(listOf(drag), sameSize, density)
        assertEquals(ActionType.SWIPE, steps[0].type)
        assertEquals(100, steps[0].swipeStart?.x)
        assertEquals(900, steps[0].swipeStart?.y)
        assertEquals(200, steps[0].swipeEnd?.y)
        assertEquals(350L, steps[0].swipeDurationMs)
    }

    @Test
    fun `동작 사이의 빈 시간이 대기 단계로 남는다`() {
        val steps = MacroRecorder.toSteps(
            listOf(tap(1, 1, 0), tap(2, 2, 3_000)),
            sameSize,
            density,
        )
        // 터치 → 대기 → 터치
        assertEquals(3, steps.size)
        assertEquals(ActionType.TAP, steps[0].type)
        assertEquals(ActionType.WAIT, steps[1].type)
        assertEquals(ActionType.TAP, steps[2].type)
        // 첫 터치가 50ms 걸렸으니 빈 시간은 2950ms
        assertEquals(2_950L, steps[1].waitMs)
    }

    @Test
    fun `아주 짧은 틈은 대기 단계로 만들지 않는다`() {
        val steps = MacroRecorder.toSteps(
            listOf(tap(1, 1, 0), tap(2, 2, 100)),
            sameSize,
            density,
        )
        assertEquals(2, steps.size)
        assertTrue(steps.none { it.type == ActionType.WAIT })
    }

    @Test
    fun `너무 긴 틈은 상한선까지만 기다린다`() {
        val steps = MacroRecorder.toSteps(
            listOf(tap(1, 1, 0), tap(2, 2, 10 * 60 * 1000)),
            sameSize,
            density,
        )
        assertEquals(MacroRecorder.MAX_WAIT_MS, steps[1].waitMs)
    }

    @Test
    fun `해상도가 다르면 기준 좌표로 되돌려 저장한다`() {
        // 기준 720x1280 매크로를 1080x1920 화면에서 녹화 → 배율 1.5배
        val mapper = CoordinateMapper(720, 1280, 1080, 1920)
        val steps = MacroRecorder.toSteps(listOf(tap(150, 300, 0)), mapper, density)
        assertEquals(100, steps[0].point?.x)
        assertEquals(200, steps[0].point?.y)
    }

    @Test
    fun `녹화한 단계는 뒤에 붙는 지연을 두지 않는다`() {
        // 동작 사이 간격은 WAIT 단계가 담당하므로 afterDelay 로 또 기다리면 안 된다.
        val steps = MacroRecorder.toSteps(
            listOf(tap(1, 1, 0), tap(2, 2, 3_000)),
            sameSize,
            density,
        )
        assertTrue(steps.all { it.afterDelayMs == 0L })
    }

    @Test
    fun `밀도가 높으면 손 떨림 허용 범위도 커진다`() {
        // 25px 움직임: 밀도 1 에서는 스와이프, 밀도 3 에서는 그냥 터치로 본다.
        val wobble = RecordedGesture(
            startX = 100, startY = 100, endX = 120, endY = 115,
            startedAt = 0, endedAt = 80, maxDistancePx = 25f,
        )
        assertEquals(
            ActionType.SWIPE,
            MacroRecorder.toSteps(listOf(wobble), sameSize, 1f)[0].type,
        )
        assertEquals(
            ActionType.TAP,
            MacroRecorder.toSteps(listOf(wobble), sameSize, 3f)[0].type,
        )
    }

    @Test
    fun `녹화한 단계에는 찾기 쉬운 이름이 붙는다`() {
        val steps = MacroRecorder.toSteps(listOf(tap(1, 1, 0)), sameSize, density, startIndex = 5)
        assertEquals("녹화 5", steps[0].name)
    }

    @Test
    fun `아무 동작도 없으면 빈 목록이다`() {
        assertTrue(MacroRecorder.toSteps(emptyList(), sameSize, density).isEmpty())
    }

    @Test
    fun `기준 해상도가 비어 있으면 현재 화면 크기를 쓴다`() {
        val mapper = MacroRecorder.mapperFor(0, 0, 1080, 2400)
        assertEquals(1080, mapper.referenceWidth)
        assertEquals(2400, mapper.referenceHeight)
        assertEquals(1f, mapper.scale, 0.001f)
    }

    @Test
    fun `거리 계산이 맞다`() {
        assertEquals(5f, MacroRecorder.distance(0, 0, 3, 4), 0.001f)
        assertEquals(0f, MacroRecorder.distance(7, 7, 7, 7), 0.001f)
    }
}
