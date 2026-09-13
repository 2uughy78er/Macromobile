package com.macromobile.inputmirror

import com.macromobile.inputmirror.input.CoordinateTransformer
import com.macromobile.inputmirror.input.MappedPoint
import com.macromobile.inputmirror.input.MirrorPathPlanner
import com.macromobile.inputmirror.input.TouchPoint
import com.macromobile.inputmirror.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPathPlannerTest {

    private val master = Region.of(0, 0, 1000, 1000)
    private val target = Region.of(1000, 0, 500, 500)
    private val transformer = CoordinateTransformer(master, target)

    private fun pt(x: Float, y: Float, t: Long) = TouchPoint(x, y, t)

    @Test
    fun `경로의 모든 점이 대상 좌표로 옮겨진다`() {
        val path = MirrorPathPlanner.mapPath(
            listOf(pt(0f, 0f, 0), pt(1000f, 1000f, 100)),
            transformer,
        )!!
        assertEquals(2, path.size)
        assertEquals(1000f, path[0].x, 0.01f)
        assertEquals(0f, path[0].y, 0.01f)
        // 오른쪽 아래 끝은 창 안으로 잘려 499 가 된다.
        assertEquals(1499f, path[1].x, 0.01f)
        assertEquals(499f, path[1].y, 0.01f)
    }

    @Test
    fun `변환할 수 없으면 경로 전체를 포기한다`() {
        // 일부만 옮기면 경로가 꺾여 엉뚱한 곳을 누른다. 그래서 통째로 null 이어야 한다.
        val broken = CoordinateTransformer(Region.EMPTY, target)
        assertNull(MirrorPathPlanner.mapPath(listOf(pt(1f, 1f, 0)), broken))
    }

    @Test
    fun `빈 경로는 변환하지 않는다`() {
        assertNull(MirrorPathPlanner.mapPath(emptyList(), transformer))
    }

    @Test
    fun `같은 자리에 머문 점은 걷어낸다`() {
        val points = listOf(
            MappedPoint(10f, 10f),
            MappedPoint(10f, 10f),
            MappedPoint(10.2f, 10f),
            MappedPoint(30f, 10f),
        )
        val cleaned = MirrorPathPlanner.dropDuplicates(points)
        assertEquals(2, cleaned.size)
        assertEquals(10f, cleaned[0].x, 0.01f)
        assertEquals(30f, cleaned[1].x, 0.01f)
    }

    @Test
    fun `첫 점은 항상 남긴다`() {
        val single = listOf(MappedPoint(5f, 5f))
        assertEquals(1, MirrorPathPlanner.dropDuplicates(single).size)
        val allSame = listOf(MappedPoint(5f, 5f), MappedPoint(5f, 5f), MappedPoint(5f, 5f))
        assertEquals(1, MirrorPathPlanner.dropDuplicates(allSame).size)
    }

    @Test
    fun `구간 길이는 실제 걸린 시간이다`() {
        val points = listOf(pt(0f, 0f, 1_000), pt(1f, 1f, 1_350))
        assertEquals(350L, MirrorPathPlanner.segmentDuration(points, 60_000L))
    }

    @Test
    fun `점이 하나뿐이면 최소 길이를 쓴다`() {
        // 0 이면 시스템이 제스처를 거부한다.
        assertEquals(
            MirrorPathPlanner.MIN_SEGMENT_MS,
            MirrorPathPlanner.segmentDuration(listOf(pt(0f, 0f, 0)), 60_000L),
        )
    }

    @Test
    fun `구간 길이는 시스템 상한을 넘지 않는다`() {
        val points = listOf(pt(0f, 0f, 0), pt(1f, 1f, 999_999))
        assertEquals(60_000L, MirrorPathPlanner.segmentDuration(points, 60_000L))
    }

    @Test
    fun `동시에 보낼 수 있는 대상 수는 시스템 한계를 따른다`() {
        assertTrue(MirrorPathPlanner.withinStrokeLimit(3, 10))
        assertTrue(MirrorPathPlanner.withinStrokeLimit(10, 10))
        assertFalse(MirrorPathPlanner.withinStrokeLimit(11, 10))
        // 대상이 없으면 보낼 것도 없다.
        assertFalse(MirrorPathPlanner.withinStrokeLimit(0, 10))
    }
}
