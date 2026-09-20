package com.macromobile.imagemacro.automation

import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.storage.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * 한 번의 매크로 실행에 걸쳐 공유되는 정보.
 *
 * 일시정지 처리를 한 곳에 모아두어, 모든 동작이 [gate] 를 지나면서 자연스럽게 멈추도록 한다.
 */
class ExecutionContext(
    val macro: Macro,
    val settings: AppSettings,
    val variables: VariableStore,
    private val pausedFlow: StateFlow<Boolean>,
    private val logger: (String, RunLogEntry.Level) -> Unit,
) {
    /** 이 단계에서 쓸 화면 검사 간격. */
    fun pollInterval(stepInterval: Long): Long =
        if (stepInterval > 0) stepInterval else settings.pollIntervalMs

    val analysisScale: Float get() = settings.analysisScale

    fun log(message: String, level: RunLogEntry.Level = RunLogEntry.Level.INFO) =
        logger(message, level)

    /**
     * 일시정지 상태면 재개될 때까지 기다린다.
     *
     * 터치·스와이프·텍스트 입력·단계 진행 직전에 반드시 호출한다.
     * 코루틴이 취소되면(= 중지) 여기서 [kotlinx.coroutines.CancellationException] 이 난다.
     */
    suspend fun gate() {
        if (pausedFlow.value) {
            log("일시정지 - 재개를 기다립니다.")
            pausedFlow.first { !it }
            log("재개되었습니다.")
        }
    }

    val isPaused: Boolean get() = pausedFlow.value

    /** 설정된 흔들림을 반영해 기다린다. */
    suspend fun sleep(ms: Long) {
        if (ms <= 0) return
        val jitter = settings.delayJitter
        val actual = if (jitter <= 0f) {
            ms
        } else {
            val span = (ms * jitter).toLong()
            (ms + Random.nextLong(-span, span + 1)).coerceAtLeast(0L)
        }
        delay(actual)
    }
}
