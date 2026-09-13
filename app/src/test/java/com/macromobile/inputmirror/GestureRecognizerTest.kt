package com.macromobile.inputmirror

import com.macromobile.inputmirror.input.GestureRecognizer
import com.macromobile.inputmirror.input.GestureState
import com.macromobile.inputmirror.input.GestureType
import com.macromobile.inputmirror.input.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 판정 규칙을 못박는 시험.
 *
 * 이 앱의 버그는 "첫 번째 터치는 TAP, 두 번째 터치는 DRAG" 였다. 그래서 여기서는
 * **순서를 여러 가지로 바꿔 놓고** 각 제스처가 제 움직임대로만 판정되는지를 본다.
 * 기기 없이 돌아가므로 빌드할 때마다 자동으로 확인된다.
 */
class GestureRecognizerTest {

    private val threshold = 24f
    private var clock = 1_000L

    private fun point(x: Float, y: Float) = TouchPoint(x, y, clock++)

    /** 누르기 하나를 흘려보낸다. 임계값 아래의 흔들림을 일부러 섞는다. */
    private fun GestureRecognizer.playTap(x: Float = 100f, y: Float = 100f): GestureType? {
        onDown(point(x, y))
        onMove(point(x + threshold * 0.3f, y))
        onMove(point(x, y + threshold * 0.3f))
        return onUp(point(x + 1f, y))?.type
    }

    /** 끌기 하나를 흘려보낸다. */
    private fun GestureRecognizer.playDrag(x: Float = 100f, y: Float = 100f): GestureType? {
        onDown(point(x, y))
        for (i in 1..6) onMove(point(x + threshold * i, y))
        return onUp(point(x + threshold * 7, y))?.type
    }

    private fun recognizer() = GestureRecognizer(threshold)

    private fun play(order: List<GestureType>): List<GestureType?> {
        val recognizer = recognizer()
        return order.map { expected ->
            if (expected == GestureType.TAP) recognizer.playTap() else recognizer.playDrag()
        }
    }

    // ------------------------------------------------------------------
    // 기본 규칙
    // ------------------------------------------------------------------

    @Test
    fun `임계값을 넘지 않으면 TAP`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        recognizer.onMove(point(100f + threshold - 1f, 100f))
        val outcome = recognizer.onUp(point(100f + threshold - 1f, 100f))
        assertEquals(GestureType.TAP, outcome?.type)
    }

    @Test
    fun `임계값과 같으면 DRAG`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        val update = recognizer.onMove(point(100f + threshold, 100f))
        assertEquals(GestureState.DRAG, update?.stateAfter)
        assertTrue(update!!.crossedThreshold)
        assertEquals(GestureType.DRAG, recognizer.onUp(point(100f + threshold, 100f))?.type)
    }

    @Test
    fun `MOVE 없이 UP 에서 임계값을 넘어도 DRAG`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        assertEquals(GestureType.DRAG, recognizer.onUp(point(400f, 400f))?.type)
    }

    @Test
    fun `DRAG 가 된 뒤 시작점으로 돌아와도 TAP 으로 되돌아가지 않는다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        recognizer.onMove(point(300f, 100f))
        recognizer.onMove(point(100f, 100f))
        assertEquals(GestureType.DRAG, recognizer.onUp(point(100f, 100f))?.type)
    }

    @Test
    fun `거리는 대각선도 제대로 잰다`() {
        val recognizer = GestureRecognizer(10f)
        recognizer.onDown(point(0f, 0f))
        // (6, 8) 은 원점에서 정확히 10 이다.
        val update = recognizer.onMove(point(6f, 8f))
        assertEquals(10f, update!!.distance, 0.001f)
        assertEquals(GestureState.DRAG, update.stateAfter)
    }

    @Test
    fun `DRAG 는 DOWN 부터 UP 까지 전체 경로를 남긴다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        repeat(4) { i -> recognizer.onMove(point(100f + threshold * (i + 1), 100f)) }
        val outcome = recognizer.onUp(point(300f, 160f))
        // DOWN 1 + MOVE 4 + UP 1
        assertEquals(6, outcome!!.session.dragPath().size)
        assertEquals(100f, outcome.session.dragPath().first().x, 0.001f)
        assertEquals(300f, outcome.session.dragPath().last().x, 0.001f)
    }

    @Test
    fun `TAP 좌표 정책은 시작점이다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 200f))
        recognizer.onMove(point(100f + threshold - 2f, 200f))
        val outcome = recognizer.onUp(point(100f + threshold - 2f, 200f))
        assertEquals(100f, outcome!!.session.tapPoint().x, 0.001f)
        assertEquals(200f, outcome.session.tapPoint().y, 0.001f)
    }

    // ------------------------------------------------------------------
    // 순서와 무관함 — 이번 수정의 핵심
    // ------------------------------------------------------------------

    @Test
    fun `TEST A - 탭 다섯 번은 모두 TAP`() {
        val order = List(5) { GestureType.TAP }
        assertEquals(order, play(order))
    }

    @Test
    fun `TEST B - 드래그 다섯 번은 모두 DRAG`() {
        val order = List(5) { GestureType.DRAG }
        assertEquals(order, play(order))
    }

    @Test
    fun `TEST C - 탭 드래그 번갈아`() {
        val order = listOf(
            GestureType.TAP, GestureType.DRAG, GestureType.TAP,
            GestureType.DRAG, GestureType.TAP, GestureType.DRAG,
        )
        assertEquals(order, play(order))
    }

    @Test
    fun `TEST D - 드래그 탭 번갈아`() {
        val order = listOf(
            GestureType.DRAG, GestureType.TAP, GestureType.DRAG,
            GestureType.TAP, GestureType.DRAG,
        )
        assertEquals(order, play(order))
    }

    @Test
    fun `TEST E - 뒤섞어도 각자 제 움직임대로`() {
        val order = listOf(
            GestureType.TAP, GestureType.TAP, GestureType.DRAG, GestureType.DRAG,
            GestureType.TAP, GestureType.DRAG, GestureType.TAP,
        )
        assertEquals(order, play(order))
    }

    @Test
    fun `가능한 모든 길이 6 순서 조합이 그대로 판정된다`() {
        // 64가지 순서를 전부 돌린다. 순서에 기대는 로직이 하나라도 남아 있으면 여기서 깨진다.
        for (mask in 0 until 64) {
            val order = (0 until 6).map {
                if ((mask shr it) and 1 == 1) GestureType.DRAG else GestureType.TAP
            }
            assertEquals("순서 $order 에서 판정이 어긋났습니다", order, play(order))
        }
    }

    @Test
    fun `같은 자리를 반복해 눌러도 매번 TAP`() {
        val recognizer = recognizer()
        repeat(10) {
            assertEquals(GestureType.TAP, recognizer.playTap(250f, 250f))
        }
    }

    // ------------------------------------------------------------------
    // 세션이 새지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `DOWN 마다 새 세션이 만들어지고 번호가 다르다`() {
        val recognizer = recognizer()
        val first = recognizer.onDown(point(10f, 10f))
        recognizer.onUp(point(10f, 10f))
        val second = recognizer.onDown(point(10f, 10f))
        assertNotEquals(first.id, second.id)
        assertEquals(GestureState.PENDING, second.state)
        assertEquals(0f, second.maxDistance, 0.001f)
    }

    @Test
    fun `UP 을 놓친 채 새 DOWN 이 와도 이전 세션을 물려받지 않는다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(10f, 10f))
        recognizer.onMove(point(500f, 500f))       // 이전 제스처는 DRAG 상태
        assertEquals(GestureState.DRAG, recognizer.state)

        recognizer.onDown(point(10f, 10f))          // UP 없이 새 DOWN
        assertEquals(GestureState.PENDING, recognizer.state)
        assertEquals(GestureType.TAP, recognizer.onUp(point(11f, 10f))?.type)
    }

    @Test
    fun `취소된 제스처는 아무것도 확정하지 않는다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(10f, 10f))
        recognizer.onMove(point(500f, 500f))
        val cancelled = recognizer.onCancel()
        assertEquals(GestureState.DRAG, cancelled?.state)
        assertNull(cancelled?.finishedType)
        assertNull(recognizer.onUp(point(500f, 500f)))
        assertEquals(GestureState.IDLE, recognizer.state)
    }

    @Test
    fun `진행 중인 제스처가 없으면 MOVE 와 UP 은 무시된다`() {
        val recognizer = recognizer()
        assertNull(recognizer.onMove(point(10f, 10f)))
        assertNull(recognizer.onUp(point(10f, 10f)))
    }

    @Test
    fun `제스처 도중 임계값을 바꿔도 진행 중인 판정은 흔들리지 않는다`() {
        val recognizer = recognizer()
        recognizer.onDown(point(100f, 100f))
        recognizer.dragThresholdPx = 1f             // 도중에 아주 작게 바꿈
        recognizer.onMove(point(100f + threshold - 2f, 100f))
        // 이 제스처는 시작할 때의 임계값(24f)으로 끝까지 판정된다.
        assertEquals(GestureType.TAP, recognizer.onUp(point(100f + threshold - 2f, 100f))?.type)
        // 다음 제스처부터 새 임계값이 적용된다.
        recognizer.onDown(point(100f, 100f))
        recognizer.onMove(point(105f, 100f))
        assertEquals(GestureType.DRAG, recognizer.onUp(point(105f, 100f))?.type)
    }

    // ------------------------------------------------------------------
    // 임계값 단위
    // ------------------------------------------------------------------

    @Test
    fun `임계값은 dp 와 밀도로 환산한다`() {
        assertEquals(24f, GestureRecognizer.thresholdPx(12f, 2f), 0.001f)
        assertEquals(36f, GestureRecognizer.thresholdPx(12f, 3f), 0.001f)
        // 같은 dp 라도 밀도가 높으면 픽셀값이 커진다 — 그래서 픽셀로 고정하면 안 된다.
        assertTrue(
            GestureRecognizer.thresholdPx(12f, 3.5f) >
                GestureRecognizer.thresholdPx(12f, 1f),
        )
    }

    @Test
    fun `기본 임계값은 요청 범위 10~20dp 안에 있다`() {
        assertTrue(GestureRecognizer.DEFAULT_THRESHOLD_DP in 10f..20f)
    }
}
