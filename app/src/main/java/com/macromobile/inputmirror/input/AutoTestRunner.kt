package com.macromobile.inputmirror.input

import com.macromobile.inputmirror.model.Region
import kotlinx.coroutines.delay

/** 한 종류의 테스트 결과 통계. */
data class TestStats(
    val name: String,
    val attempts: Int = 0,
    val success: Int = 0,
    val cancelled: Int = 0,
    val rejected: Int = 0,
    val exception: Int = 0,
    val latenciesMs: List<Long> = emptyList(),
) {
    val failures: Int get() = attempts - success
    val successRate: Float get() = if (attempts == 0) 0f else success * 100f / attempts
    val avgLatency: Long get() = if (latenciesMs.isEmpty()) 0 else latenciesMs.average().toLong()
    val minLatency: Long get() = latenciesMs.minOrNull() ?: 0
    val maxLatency: Long get() = latenciesMs.maxOrNull() ?: 0

    fun summary(): String =
        "$name: ${success}/${attempts} (${"%.1f".format(successRate)}%) " +
            "취소 $cancelled · 거부 $rejected · 예외 $exception · " +
            "지연 평균 ${avgLatency}ms (최소 ${minLatency} / 최대 ${maxLatency})"
}

/** 자동 테스트 한 항목. 마스터 영역 안의 상대 위치로 정의해 해상도에 매이지 않는다. */
data class TestCase(
    val name: String,
    /** 시작점(마스터 영역 안 0~1 상대 좌표). */
    val fromX: Float,
    val fromY: Float,
    /** 끝점. 시작점과 같으면 탭이다. */
    val toX: Float = fromX,
    val toY: Float = fromY,
    val steps: Int = 1,
    val durationMs: Long = 0,
) {
    val isTap: Boolean get() = fromX == toX && fromY == toY

    companion object {
        /** 요구사항 10단계의 TEST 1~9. 상대 좌표라 어떤 해상도에서도 같은 곳을 짚는다. */
        val STANDARD = listOf(
            TestCase("TEST1 중앙 탭", 0.5f, 0.5f),
            TestCase("TEST2 좌상단 탭", 0.15f, 0.15f),
            TestCase("TEST3 우상단 탭", 0.85f, 0.15f),
            TestCase("TEST4 좌하단 탭", 0.15f, 0.85f),
            TestCase("TEST5 우하단 탭", 0.85f, 0.85f),
            TestCase("TEST6 중앙→우 스와이프", 0.3f, 0.5f, 0.8f, 0.5f, steps = 8, durationMs = 300),
            TestCase("TEST7 중앙→좌 스와이프", 0.7f, 0.5f, 0.2f, 0.5f, steps = 8, durationMs = 300),
            TestCase("TEST8 상→하 스와이프", 0.5f, 0.2f, 0.5f, 0.8f, steps = 8, durationMs = 300),
            TestCase("TEST9 하→상 스와이프", 0.5f, 0.8f, 0.5f, 0.2f, steps = 8, durationMs = 300),
        )
    }
}

/**
 * 손으로 반복하지 않고 같은 동작을 정해진 횟수만큼 자동으로 흘려보낸다.
 *
 * "될 때도 있고 안 될 때도 있다"를 눈대중이 아니라 **성공률**로 확인하기 위한 장치다.
 * 마스터 View 좌표를 만들어 전송기에 그대로 넣으므로, 사람이 손가락으로 하는 것과
 * 같은 경로를 탄다.
 */
class AutoTestRunner(private val dispatcher: GestureDispatcher) {

    /**
     * 테스트 하나를 [repeat] 회 실행한다.
     *
     * @param master 마스터 영역(View 공간)
     * @param onProgress 매 회차가 끝날 때 호출된다.
     */
    suspend fun run(
        case: TestCase,
        master: Region,
        repeat: Int,
        gapMs: Long = 250,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        if (!master.isValid) return
        repeat(repeat) { index ->
            playOnce(case, master)
            onProgress(index + 1, repeat)
            delay(gapMs)
        }
    }

    private suspend fun playOnce(case: TestCase, master: Region) {
        val startX = master.left + case.fromX * master.width
        val startY = master.top + case.fromY * master.height
        val endX = master.left + case.toX * master.width
        val endY = master.top + case.toY * master.height
        val now = System.currentTimeMillis()

        dispatcher.onDown(TouchPoint(startX, startY, now))
        if (case.isTap) {
            dispatcher.onUp(listOf(TouchPoint(startX, startY, now + TAP_HOLD_MS)))
            return
        }

        val stepDelay = (case.durationMs / case.steps.coerceAtLeast(1)).coerceAtLeast(1L)
        for (i in 1..case.steps) {
            val t = i.toFloat() / case.steps
            val point = TouchPoint(
                startX + (endX - startX) * t,
                startY + (endY - startY) * t,
                now + stepDelay * i,
            )
            dispatcher.onMove(listOf(point))
            delay(stepDelay)
        }
        dispatcher.onUp(
            listOf(TouchPoint(endX, endY, now + case.durationMs + stepDelay)),
        )
    }

    private companion object {
        const val TAP_HOLD_MS = 60L
    }
}
