package com.macromobile.inputmirror

import com.macromobile.inputmirror.diag.TestStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 단계 구분이 실제로 "아래 단계를 포함"하는지 확인한다.
 *
 * 이 성질이 깨지면 STEP 을 내려도 기능이 남아 있어 범인을 가려낼 수 없다.
 */
class TestStepTest {

    @Test
    fun `단계 번호는 1부터 10까지 빠짐없이 있다`() {
        assertEquals((1..10).toList(), TestStep.entries.map { it.number })
    }

    @Test
    fun `높은 단계는 낮은 단계를 모두 포함한다`() {
        TestStep.entries.forEach { high ->
            TestStep.entries.filter { it.number <= high.number }.forEach { low ->
                assertTrue("${high.name} 이 ${low.name} 을 포함해야 한다", high.includes(low))
            }
        }
    }

    @Test
    fun `낮은 단계는 높은 단계를 포함하지 않는다`() {
        assertFalse(TestStep.EMPTY.includes(TestStep.ALL_TARGETS))
        assertFalse(TestStep.TOUCH.includes(TestStep.ONE_TARGET))
    }

    @Test
    fun `STEP 8 이하에서는 주입 대상이 없다`() {
        TestStep.entries.filter { it.number <= 8 }.forEach {
            assertEquals("${it.name} 에서는 대상이 0개여야 한다", 0, it.targetCount)
        }
    }

    @Test
    fun `STEP 9 는 대상 하나 STEP 10 은 셋`() {
        assertEquals(1, TestStep.ONE_TARGET.targetCount)
        assertEquals(3, TestStep.ALL_TARGETS.targetCount)
    }

    @Test
    fun `기본 단계는 전체 단계다`() {
        assertEquals(TestStep.ALL_TARGETS, TestStep.FULL)
        assertEquals(TestStep.ALL_TARGETS, TestStep.ofNumber(10))
        assertEquals(TestStep.EMPTY, TestStep.ofNumber(1))
        // 범위 밖은 전체 단계로 떨어진다. 조용히 이상한 단계로 가지 않는다.
        assertEquals(TestStep.FULL, TestStep.ofNumber(99))
    }
}
