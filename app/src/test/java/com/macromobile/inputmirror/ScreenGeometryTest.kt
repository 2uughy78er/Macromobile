package com.macromobile.inputmirror

import com.macromobile.inputmirror.input.ScreenGeometry
import com.macromobile.inputmirror.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * View 좌표와 Screen 좌표를 섞어 쓴 것이 좌표 어긋남의 원인이었다.
 * 두 공간의 변환이 정확히 왕복하는지 고정한다.
 */
class ScreenGeometryTest {

    @Test
    fun `뷰가 화면 원점에 있으면 좌표가 그대로다`() {
        val g = ScreenGeometry(0, 0)
        assertEquals(100f, g.toScreenX(100f), 0.01f)
        assertEquals(200f, g.toScreenY(200f), 0.01f)
        assertTrue(g.isIdentity)
    }

    @Test
    fun `상태바만큼 내려간 뷰는 세로로 그만큼 더해진다`() {
        // 문제의 핵심: windowInsetsPadding 때문에 뷰가 상태바 아래에서 시작한다.
        val g = ScreenGeometry(0, 48)
        assertEquals(100f, g.toScreenX(100f), 0.01f)
        assertEquals(248f, g.toScreenY(200f), 0.01f)
        assertFalse(g.isIdentity)
    }

    @Test
    fun `가로 인셋이 있으면 가로도 밀린다`() {
        // 가로 모드 컷아웃이나 내비게이션 바가 옆에 있으면 가로 원점도 0 이 아니다.
        val g = ScreenGeometry(64, 40)
        assertEquals(164f, g.toScreenX(100f), 0.01f)
        assertEquals(140f, g.toScreenY(100f), 0.01f)
    }

    @Test
    fun `화면 좌표에서 뷰 좌표로 정확히 되돌아온다`() {
        val g = ScreenGeometry(37, 91)
        listOf(0f, 1f, 539.5f, 2303f).forEach { v ->
            assertEquals(v, g.toViewX(g.toScreenX(v)), 0.001f)
            assertEquals(v, g.toViewY(g.toScreenY(v)), 0.001f)
        }
    }

    @Test
    fun `영역 전체가 같은 만큼 옮겨진다`() {
        val g = ScreenGeometry(10, 48)
        val viewRegion = Region.of(1152, 0, 1152, 533)
        val screen = g.toScreen(viewRegion)
        assertEquals(1162, screen.left)
        assertEquals(48, screen.top)
        // 크기는 변하지 않는다. 옮기기만 한다.
        assertEquals(viewRegion.width, screen.width)
        assertEquals(viewRegion.height, screen.height)
    }

    @Test
    fun `요구사항에 나온 실제 값으로 확인한다`() {
        // 4분할 1152x533, MASTER (939,468) 터치. 뷰가 상태바 아래 48px 에서 시작한다면.
        val g = ScreenGeometry(0, 48)
        // TARGET 1 의 뷰 좌표는 (939+1152, 468) = (2091, 468)
        val target1ViewX = 939f + 1152f
        val target1ViewY = 468f
        // 주입해야 하는 실제 화면 좌표는 세로로 48 더 아래다.
        assertEquals(2091f, g.toScreenX(target1ViewX), 0.01f)
        assertEquals(516f, g.toScreenY(target1ViewY), 0.01f)
        // 이 48px 을 빠뜨린 것이 "미묘하게 어긋남"의 정체다.
    }
}
