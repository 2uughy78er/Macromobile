package com.macromobile.inputmirror

import com.macromobile.inputmirror.model.LayoutStatus
import com.macromobile.inputmirror.model.MirrorLayout
import com.macromobile.inputmirror.model.MirrorRegion
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.model.ScreenFingerprint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 배치 규칙을 못박는 시험.
 *
 * 영역 좌표는 사용자가 화면에서 직접 지정한 실제 값이고, **그 좌표가 언제 쓸 수 없게
 * 되는지**를 정확히 아는 것이 중요하다. 회전이나 멀티윈도우 크기 변경 뒤에도 옛 좌표를
 * 그대로 쓰면 엉뚱한 곳에 입력이 들어가기 때문이다.
 */
class MirrorLayoutTest {

    private val screen = ScreenFingerprint(2400, 1600, 0)

    private fun region(
        id: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        enabled: Boolean = true,
        deliver: Boolean = true,
    ) = MirrorRegion(
        id = id,
        name = id.uppercase(),
        bounds = Region(l, t, r, b),
        enabled = enabled,
        deliverInput = deliver,
    )

    /** 2×2 로 나눈 정상 배치. */
    private fun layout() = MirrorLayout(
        master = region("master", 0, 0, 1200, 800),
        targets = listOf(
            region("target_1", 1200, 0, 2400, 800),
            region("target_2", 0, 800, 1200, 1600),
            region("target_3", 1200, 800, 2400, 1600),
        ),
        screen = screen,
    )

    @Test
    fun `제대로 지정된 배치는 준비됨`() {
        assertEquals(LayoutStatus.Ready, layout().status(screen))
        assertTrue(layout().status(screen).isReady)
    }

    @Test
    fun `MASTER 가 없으면 시작할 수 없다`() {
        val status = layout().withMaster(null).status(screen)
        assertTrue(status is LayoutStatus.NoMaster)
        assertFalse(status.isReady)
    }

    @Test
    fun `TARGET 이 전부 꺼져 있으면 시작할 수 없다`() {
        val off = layout().copy(
            targets = layout().targets.map { it.copy(enabled = false) },
        )
        assertTrue(off.status(screen) is LayoutStatus.NoTarget)
    }

    @Test
    fun `입력 전달만 꺼도 대상에서 빠진다`() {
        val paused = layout().copy(
            targets = layout().targets.map { it.copy(deliverInput = false) },
        )
        assertEquals(0, paused.activeTargets.size)
        assertTrue(paused.status(screen) is LayoutStatus.NoTarget)
    }

    @Test
    fun `영역이 겹치면 시작할 수 없다`() {
        val overlapping = layout().withTarget(
            region("target_1", 600, 400, 1800, 1200),   // MASTER 와 겹침
        )
        val status = overlapping.status(screen)
        assertTrue("겹침을 잡아내야 한다", status is LayoutStatus.Overlap)
    }

    @Test
    fun `맞닿기만 하는 영역은 겹친 것이 아니다`() {
        // 0..1200 과 1200..2400 은 경계를 공유할 뿐 겹치지 않는다.
        assertFalse(Region(0, 0, 1200, 800).overlaps(Region(1200, 0, 2400, 800)))
        assertTrue(layout().status(screen).isReady)
    }

    @Test
    fun `너무 작은 영역은 지정 실수로 본다`() {
        val tiny = layout().withTarget(region("target_1", 10, 10, 30, 30))
        val status = tiny.status(screen)
        assertTrue(status is LayoutStatus.TargetTooSmall)
    }

    @Test
    fun `화면이 회전하면 저장된 좌표를 그대로 쓰지 않는다`() {
        val rotated = ScreenFingerprint(1600, 2400, 1)
        val status = layout().status(rotated)
        assertTrue(status is LayoutStatus.ScreenChanged)
        assertTrue(status.reason.contains("다시 확인"))
    }

    @Test
    fun `멀티윈도우 크기가 바뀌어도 마찬가지다`() {
        val resized = ScreenFingerprint(2400, 1200, 0)
        assertTrue(layout().status(resized) is LayoutStatus.ScreenChanged)
    }

    @Test
    fun `화면 조건을 모르면 좌표 검사를 막지 않는다`() {
        // 아직 화면 정보를 못 읽은 상태에서 공연히 막아 세우지는 않는다.
        val unknown = ScreenFingerprint()
        assertTrue(layout().status(unknown).isReady)
    }

    @Test
    fun `TARGET 은 개수 제한 없이 더할 수 있다`() {
        var current = MirrorLayout(master = region("master", 0, 0, 600, 600), screen = screen)
        repeat(6) { index ->
            val id = MirrorLayout.nextTargetId(current.targets)
            current = current.withTarget(
                region(id, 600 + index * 300, 0, 900 + index * 300, 600),
            )
        }
        assertEquals(6, current.targets.size)
        assertEquals(6, current.targets.map { it.id }.toSet().size)
        assertTrue(current.status(screen).isReady)
    }

    @Test
    fun `같은 id 로 다시 넣으면 덮어쓴다`() {
        val updated = layout().withTarget(region("target_1", 1200, 0, 2400, 400))
        assertEquals(3, updated.targets.size)
        assertEquals(400, updated.targets.first { it.id == "target_1" }.bounds.bottom)
    }

    @Test
    fun `TARGET 을 지울 수 있다`() {
        val removed = layout().withoutTarget("target_2")
        assertEquals(2, removed.targets.size)
        assertFalse(removed.targets.any { it.id == "target_2" })
    }

    @Test
    fun `배치는 저장했다 그대로 읽어올 수 있다`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val text = json.encodeToString(MirrorLayout.serializer(), layout())
        val restored = json.decodeFromString(MirrorLayout.serializer(), text)
        assertEquals(layout(), restored)
        assertTrue(restored.status(screen).isReady)
    }

    @Test
    fun `크기가 서로 다른 영역도 정상 배치다`() {
        val mixed = MirrorLayout(
            master = region("master", 0, 0, 800, 600),
            targets = listOf(
                region("target_1", 800, 0, 1800, 750),
                region("target_2", 0, 700, 1200, 1500),
                region("target_3", 1300, 800, 2200, 1700),
            ),
            screen = ScreenFingerprint(2400, 1800, 0),
        )
        assertTrue(mixed.status(ScreenFingerprint(2400, 1800, 0)).isReady)
    }

    @Test
    fun `요약은 실제 좌표를 그대로 보여준다`() {
        val text = layout().describe()
        assertTrue(text.contains("2400 × 1600"))
        assertTrue(text.contains("L0 T0 R1200 B800"))
        assertTrue(text.contains("TARGET3"))
    }
}
