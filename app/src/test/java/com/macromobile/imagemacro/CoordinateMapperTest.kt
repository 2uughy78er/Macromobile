package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.RefPoint
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.vision.CoordinateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun `같은 해상도면 좌표가 그대로다`() {
        val mapper = CoordinateMapper(1080, 1920, 1080, 1920)
        assertEquals(1f, mapper.scale, 0.0001f)
        assertEquals(540, mapper.toScreenX(540))
        assertEquals(960, mapper.toScreenY(960))
        assertTrue(mapper.isIdentity)
    }

    @Test
    fun `비율이 같으면 배율만 곱해진다`() {
        // 720x1280 로 만든 매크로를 1080x1920 화면에서 실행 (둘 다 9:16)
        val mapper = CoordinateMapper(720, 1280, 1080, 1920)
        assertEquals(1.5f, mapper.scale, 0.0001f)
        assertEquals(0f, mapper.offsetX, 0.01f)
        assertEquals(0f, mapper.offsetY, 0.01f)
        assertEquals(150, mapper.toScreenX(100))
        assertEquals(300, mapper.toScreenY(200))
    }

    @Test
    fun `화면이 더 길면 위아래 여백이 생긴다`() {
        // 9:16 기준을 9:20 화면에서: 가로가 꽉 차고 세로에 여백이 생긴다.
        val mapper = CoordinateMapper(720, 1280, 1080, 2400)
        assertEquals(1.5f, mapper.scale, 0.0001f)
        assertEquals(0f, mapper.offsetX, 0.01f)
        assertEquals(240f, mapper.offsetY, 0.01f)
        assertEquals(240, mapper.toScreenY(0))
    }

    @Test
    fun `화면이 더 넓으면 좌우 여백이 생긴다`() {
        val mapper = CoordinateMapper(720, 1280, 1600, 1280)
        assertEquals(1f, mapper.scale, 0.0001f)
        assertEquals(440f, mapper.offsetX, 0.01f)
        assertEquals(440, mapper.toScreenX(0))
    }

    @Test
    fun `화면 좌표에서 기준 좌표로 되돌릴 수 있다`() {
        val mapper = CoordinateMapper(720, 1280, 1080, 2400)
        listOf(RefPoint(0, 0), RefPoint(360, 640), RefPoint(719, 1279)).forEach { p ->
            val (sx, sy) = mapper.toScreen(p)
            val back = mapper.toRefPoint(sx, sy)
            assertEquals(p.x, back.x)
            assertEquals(p.y, back.y)
        }
    }

    @Test
    fun `길이 변환에는 여백이 더해지지 않는다`() {
        val mapper = CoordinateMapper(720, 1280, 1080, 2400)
        // 오프셋 10px 은 배율만 적용되어야 한다(15px). 여백 240 이 더해지면 안 된다.
        assertEquals(15, mapper.scaleLength(10))
        assertEquals(-15, mapper.scaleLength(-10))
    }

    @Test
    fun `ROI 는 화면 안으로 잘린다`() {
        val mapper = CoordinateMapper(720, 1280, 720, 1280)
        val rect = mapper.toScreenRect(Roi(600, 1200, 400, 400))
        assertNotNull(rect)
        assertEquals(600, rect!!.x)
        assertEquals(1200, rect.y)
        assertEquals(120, rect.width)
        assertEquals(80, rect.height)
    }

    @Test
    fun `화면 밖 ROI 는 null 이다`() {
        val mapper = CoordinateMapper(720, 1280, 720, 1280)
        assertNull(mapper.toScreenRect(Roi(800, 1400, 100, 100)))
        assertNull(mapper.toScreenRect(null))
        assertNull(mapper.toScreenRect(Roi(0, 0, 0, 0)))
    }

    @Test
    fun `기준 해상도를 모르면 보정하지 않는다`() {
        val mapper = CoordinateMapper(0, 0, 1080, 1920)
        assertEquals(1f, mapper.scale, 0.0001f)
        assertEquals(500, mapper.toScreenX(500))
    }
}
