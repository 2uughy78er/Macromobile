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

    /** 이 항목이 **당연히** 어떤 종류로 판정되어야 하는가. 검사의 기준값이다. */
    val expectedType: GestureType get() = if (isTap) GestureType.TAP else GestureType.DRAG

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

        private fun tap(n: Int) = TestCase("탭$n", 0.35f + 0.06f * n, 0.5f)
        private fun drag(n: Int) = TestCase(
            "드래그$n", 0.25f, 0.3f + 0.05f * n, 0.75f, 0.3f + 0.05f * n,
            steps = 8, durationMs = 240,
        )

        /**
         * 순서 섞기 검사.
         *
         * 이 앱의 옛 버그는 "몇 번째 터치인가"로 TAP/DRAG 가 갈린 것이었다. 그래서 순서만
         * 바꾼 묶음을 여러 개 두고, **각 제스처가 순서와 무관하게 제 움직임대로 판정되는지**를
         * 기댓값과 대조해 자동으로 확인한다.
         */
        val SEQUENCES = listOf(
            TestSequence("TEST A · 탭 5연속", (1..5).map { tap(it) }),
            TestSequence("TEST B · 드래그 5연속", (1..5).map { drag(it) }),
            TestSequence(
                "TEST C · 탭→드래그 번갈아",
                listOf(tap(1), drag(1), tap(2), drag(2), tap(3), drag(3)),
            ),
            TestSequence(
                "TEST D · 드래그→탭 번갈아",
                listOf(drag(1), tap(1), drag(2), tap(2), drag(3)),
            ),
            TestSequence(
                "TEST E · 뒤섞기",
                listOf(tap(1), tap(2), drag(1), drag(2), tap(3), drag(3), tap(4)),
            ),
        )
    }
}

/** 순서를 섞은 제스처 묶음. */
data class TestSequence(val name: String, val cases: List<TestCase>)

/** 한 제스처의 검사 결과. */
data class SequenceStep(
    val caseName: String,
    val expected: GestureType,
    val actual: GestureType?,
) {
    val ok: Boolean get() = actual == expected
}

/** 묶음 하나의 검사 결과. */
data class SequenceResult(val name: String, val steps: List<SequenceStep>) {
    val passed: Int get() = steps.count { it.ok }
    val allOk: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }

    fun summary(): String = buildString {
        append(if (allOk) "✔ " else "✘ ")
        append(name).append("  ").append(passed).append('/').append(steps.size)
        append("  [")
        append(steps.joinToString(" ") { step ->
            val actual = step.actual?.label ?: "없음"
            if (step.ok) actual else "${step.expected.label}→$actual"
        })
        append(']')
    }
}

/**
 * 제스처를 흘려넣는 곳.
 *
 * 자동 테스트는 전송기에 바로 넣지 않고 **사람 손가락과 똑같은 입구**로 넣는다.
 * 그래야 판정기를 실제로 통과하고, 검사 결과가 실제 동작의 증거가 된다.
 */
interface GestureInput {
    fun feedDown(point: TouchPoint)
    fun feedMove(point: TouchPoint)

    /** 확정된 종류를 돌려준다. 진행 중인 제스처가 없으면 null. */
    fun feedUp(point: TouchPoint): GestureType?

    /** 현재 적용 중인 DRAG 임계값(픽셀). 탭의 흔들림 폭을 이 값 아래로 잡는 데 쓴다. */
    val dragThresholdPx: Float
}

/**
 * 손으로 반복하지 않고 같은 동작을 정해진 횟수만큼 자동으로 흘려보낸다.
 *
 * "될 때도 있고 안 될 때도 있다"를 눈대중이 아니라 **성공률**로 확인하기 위한 장치다.
 */
class AutoTestRunner(private val input: GestureInput) {

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

    /** 순서를 섞은 묶음을 돌리고, 각 제스처가 기댓값대로 판정됐는지 대조한다. */
    suspend fun runSequence(
        sequence: TestSequence,
        master: Region,
        gapMs: Long = 250,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SequenceResult {
        if (!master.isValid) return SequenceResult(sequence.name, emptyList())
        val steps = ArrayList<SequenceStep>(sequence.cases.size)
        sequence.cases.forEachIndexed { index, case ->
            val actual = playOnce(case, master)
            steps += SequenceStep(case.name, case.expectedType, actual)
            onProgress(index + 1, sequence.cases.size)
            delay(gapMs)
        }
        return SequenceResult(sequence.name, steps)
    }

    /** 제스처 하나를 흘려보내고 판정된 종류를 돌려준다. */
    private suspend fun playOnce(case: TestCase, master: Region): GestureType? {
        val startX = master.left + case.fromX * master.width
        val startY = master.top + case.fromY * master.height
        val endX = master.left + case.toX * master.width
        val endY = master.top + case.toY * master.height
        val now = System.currentTimeMillis()

        input.feedDown(TouchPoint(startX, startY, now))

        if (case.isTap) {
            // 진짜 손가락은 누를 때도 조금 흔들린다. 임계값 아래의 흔들림을 일부러 넣어
            // "흔들려도 TAP" 을 확인한다. 임계값에 비례시키므로 기기가 달라도 안전하다.
            val jitter = input.dragThresholdPx * TAP_JITTER_RATIO
            input.feedMove(TouchPoint(startX + jitter, startY, now + TAP_HOLD_MS / 3))
            input.feedMove(TouchPoint(startX, startY + jitter, now + TAP_HOLD_MS * 2 / 3))
            return input.feedUp(TouchPoint(startX, startY, now + TAP_HOLD_MS))
        }

        val stepDelay = (case.durationMs / case.steps.coerceAtLeast(1)).coerceAtLeast(1L)
        for (i in 1..case.steps) {
            val t = i.toFloat() / case.steps
            input.feedMove(
                TouchPoint(
                    startX + (endX - startX) * t,
                    startY + (endY - startY) * t,
                    now + stepDelay * i,
                ),
            )
            delay(stepDelay)
        }
        return input.feedUp(
            TouchPoint(endX, endY, now + case.durationMs + stepDelay),
        )
    }

    private companion object {
        const val TAP_HOLD_MS = 60L

        /** 탭에 섞는 흔들림의 크기. 임계값의 이 비율만큼이라 절대 넘지 않는다. */
        const val TAP_JITTER_RATIO = 0.4f
    }
}
