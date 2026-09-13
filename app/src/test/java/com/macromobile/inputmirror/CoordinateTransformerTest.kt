package com.macromobile.inputmirror

import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.model.FitMode
import com.macromobile.inputmirror.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformerTest {

    @Test
    fun `크기와 위치가 같으면 좌표가 그대로다`() {
        val r = Region.of(0, 0, 1000, 1000)
        val t = CoordinateTransformer(r, r)
        val p = t.map(300f, 500f)!!
        assertEquals(300f, p.x, 0.01f)
        assertEquals(500f, p.y, 0.01f)
    }

    @Test
    fun `대상 창이 옆에 있으면 그만큼 밀려서 찍힌다`() {
        val master = Region.of(0, 0, 500, 1000)
        val target = Region.of(500, 0, 500, 1000)
        val p = CoordinateTransformer(master, target).map(100f, 200f)!!
        assertEquals(600f, p.x, 0.01f)
        assertEquals(200f, p.y, 0.01f)
    }

    @Test
    fun `대상 창이 작으면 상대 위치로 줄여서 찍는다`() {
        // 1080x1920 마스터 → 720x1280 대상. 비율이 같으므로 정확히 3분의 2.
        val master = Region.of(0, 0, 1080, 1920)
        val target = Region.of(0, 0, 720, 1280)
        val p = CoordinateTransformer(master, target).map(540f, 960f)!!
        assertEquals(360f, p.x, 0.01f)
        assertEquals(640f, p.y, 0.01f)
    }

    @Test
    fun `모서리는 모서리로 간다`() {
        val master = Region.of(0, 0, 1080, 1920)
        val target = Region.of(100, 200, 540, 960)
        val t = CoordinateTransformer(master, target)
        val topLeft = t.map(0f, 0f)!!
        assertEquals(100f, topLeft.x, 0.01f)
        assertEquals(200f, topLeft.y, 0.01f)
        val bottomRight = t.map(1080f, 1920f)!!
        assertEquals(640f, bottomRight.x, 0.01f)
        assertEquals(1160f, bottomRight.y, 0.01f)
    }

    @Test
    fun `비율이 다를 때 FIT 은 여백을 두고 가운데 맞춘다`() {
        // 세로로 긴 마스터(1:2)를 정사각 대상(1:1)에 넣으면 좌우에 여백이 생긴다.
        val master = Region.of(0, 0, 500, 1000)
        val target = Region.of(0, 0, 1000, 1000)
        val t = CoordinateTransformer(master, target, FitMode.FIT)
        // 배율은 작은 쪽(1.0)을 따르므로 그려지는 폭은 500, 좌우 여백은 각각 250.
        val center = t.map(250f, 500f)!!
        assertEquals(500f, center.x, 0.01f)
        assertEquals(500f, center.y, 0.01f)
        val left = t.map(0f, 0f)!!
        assertEquals(250f, left.x, 0.01f)
        assertEquals(0f, left.y, 0.01f)
    }

    @Test
    fun `비율이 다를 때 STRETCH 는 창 전체를 채운다`() {
        val master = Region.of(0, 0, 500, 1000)
        val target = Region.of(0, 0, 1000, 1000)
        val t = CoordinateTransformer(master, target, FitMode.STRETCH)
        val left = t.map(0f, 0f)!!
        assertEquals(0f, left.x, 0.01f)
        val right = t.map(500f, 1000f)!!
        assertEquals(1000f, right.x, 0.01f)
        assertEquals(1000f, right.y, 0.01f)
    }

    @Test
    fun `비율이 같으면 FIT 과 STRETCH 결과가 같다`() {
        val master = Region.of(0, 0, 1080, 1920)
        val target = Region.of(0, 0, 540, 960)
        val fit = CoordinateTransformer(master, target, FitMode.FIT).map(200f, 400f)!!
        val stretch = CoordinateTransformer(master, target, FitMode.STRETCH).map(200f, 400f)!!
        assertEquals(fit.x, stretch.x, 0.01f)
        assertEquals(fit.y, stretch.y, 0.01f)
    }

    @Test
    fun `상대 위치는 0에서 1 사이로 나온다`() {
        val master = Region.of(100, 100, 400, 800)
        val t = CoordinateTransformer(master, master)
        val rel = t.relative(300f, 500f)
        assertEquals(0.5f, rel.x, 0.01f)
        assertEquals(0.5f, rel.y, 0.01f)
    }

    @Test
    fun `창 크기를 모르면 변환하지 않는다`() {
        // 크기를 모르는 채로 좌표를 보내면 엉뚱한 앱이 터치를 받는다. 그래서 null 이어야 한다.
        val t = CoordinateTransformer(Region.EMPTY, Region.of(0, 0, 100, 100))
        assertNull(t.map(10f, 10f))
        assertFalse(t.isUsable)
    }

    @Test
    fun `대상 창 밖으로 나간 좌표는 창 안으로 잘린다`() {
        val master = Region.of(0, 0, 1000, 1000)
        val target = Region.of(0, 0, 500, 500)
        // 수동 보정으로 일부러 밖으로 밀어낸다.
        val t = CoordinateTransformer(master, target, FitMode.FIT, offsetX = 9_999f)
        val raw = t.map(500f, 500f)!!
        assertTrue(raw.x > target.right)
        val clamped = t.mapClamped(500f, 500f)!!
        assertEquals(499f, clamped.x, 0.01f)
        assertFalse(t.isInsideTarget(500f, 500f))
    }

    @Test
    fun `수동 배율은 대상 창 중심을 기준으로 적용된다`() {
        val r = Region.of(0, 0, 1000, 1000)
        val t = CoordinateTransformer(r, r, FitMode.FIT, scaleX = 2f, scaleY = 2f)
        // 중심은 움직이지 않는다.
        val center = t.map(500f, 500f)!!
        assertEquals(500f, center.x, 0.01f)
        // 중심에서 100 떨어진 점은 200 떨어진 곳으로 간다.
        val off = t.map(600f, 500f)!!
        assertEquals(700f, off.x, 0.01f)
    }

    @Test
    fun `수동 이동값은 그대로 더해진다`() {
        val r = Region.of(0, 0, 1000, 1000)
        val t = CoordinateTransformer(r, r, FitMode.FIT, offsetX = 25f, offsetY = -10f)
        val p = t.map(100f, 100f)!!
        assertEquals(125f, p.x, 0.01f)
        assertEquals(90f, p.y, 0.01f)
    }

    @Test
    fun `영역 포함 판정이 경계에서 올바르다`() {
        val r = Region.of(10, 20, 100, 200)
        assertTrue(r.contains(10f, 20f))
        assertTrue(r.contains(109f, 219f))
        assertFalse(r.contains(110f, 220f))
        assertFalse(r.contains(9f, 20f))
    }
}
