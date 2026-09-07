package com.macromobile.imagemacro.automation

import android.util.Log
import com.macromobile.imagemacro.input.GestureController
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.service.MacroAccessibilityService
import com.macromobile.imagemacro.service.ScreenCaptureManager
import com.macromobile.imagemacro.storage.AppSettings
import com.macromobile.imagemacro.storage.TemplateFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * 매크로 실행 엔진.
 *
 * 상태 머신: IDLE → PREPARING → RUNNING ⇄ PAUSED → (TARGET_FOUND | STOPPED | ERROR)
 *
 * 이 클래스는 특정 앱이나 게임을 전혀 알지 못한다. 사용자가 만든 단계 목록과
 * 사용자가 등록한 이미지만 보고 동작하며, 어떤 화면 이미지도 밖으로 내보내지 않는다.
 */
class MacroEngine(
    private val scope: CoroutineScope,
    private val capture: ScreenCaptureManager,
    private val executor: StepExecutor,
    private val monitor: TargetMonitor,
    private val gestures: GestureController,
    private val files: TemplateFiles,
) {
    private val _status = MutableStateFlow(MacroStatus())
    val status: StateFlow<MacroStatus> = _status.asStateFlow()

    private val paused = MutableStateFlow(false)
    private val variables = VariableStore()
    private val startLock = Mutex()

    private var runJob: Job? = null

    val isRunning: Boolean get() = runJob?.isActive == true

    /**
     * 매크로를 시작한다.
     *
     * @return 시작하지 못한 이유. 성공하면 null.
     */
    suspend fun start(macro: Macro, settings: AppSettings): String? = startLock.withLock {
        if (isRunning) return "이미 매크로가 실행 중입니다."

        // 권한 확인 - 없으면 아무 것도 실행하지 않고 이유를 알려준다.
        if (!MacroAccessibilityService.isConnected) {
            return "접근성 서비스가 켜져 있지 않습니다. 설정에서 먼저 허용해주세요."
        }
        if (!capture.active.value) {
            return "화면 캡처 권한이 없습니다. 화면 캡처를 먼저 허용해주세요."
        }
        if (macro.enabledSteps.isEmpty()) {
            return "실행할 단계가 없습니다. 매크로를 편집해 단계를 추가해주세요."
        }

        paused.value = false
        variables.reset(macro.initialVariables)
        gestures.jitterPx = settings.tapJitterPx

        _status.value = MacroStatus(
            state = RunState.PREPARING,
            macroId = macro.id,
            macroName = macro.displayName(),
            totalSteps = macro.enabledSteps.size,
            totalCycles = macro.repeat.count,
            startedAt = System.currentTimeMillis(),
            log = listOf(RunLogEntry(message = "'${macro.displayName()}' 준비 중")),
        )

        runJob = scope.launch { runLoop(macro, settings) }
        return null
    }

    fun pause() {
        if (_status.value.state != RunState.RUNNING) return
        paused.value = true
        _status.update { it.copy(state = RunState.PAUSED) }
        appendLog("일시정지했습니다.")
    }

    fun resume() {
        val state = _status.value.state
        if (state != RunState.PAUSED && state != RunState.TARGET_FOUND) return
        paused.value = false
        if (state == RunState.TARGET_FOUND) {
            // 타겟 발견으로 멈춘 뒤 "다시 실행"을 누르면 새 실행이 필요하다.
            appendLog("타겟 발견 상태에서는 다시 시작해야 합니다.", RunLogEntry.Level.WARN)
            return
        }
        _status.update { it.copy(state = RunState.RUNNING) }
        appendLog("재개했습니다.")
    }

    fun stop(reason: String = "사용자가 중지했습니다.") {
        monitor.stop()
        paused.value = false
        runJob?.cancel()
        runJob = null
        val current = _status.value
        if (current.state != RunState.TARGET_FOUND) {
            _status.update { it.copy(state = RunState.STOPPED, stepIndex = -1) }
        }
        appendLog(reason)
    }

    /** 타겟 발견 화면을 닫고 IDLE 로 되돌린다. */
    fun clearTargetFound() {
        if (_status.value.state == RunState.TARGET_FOUND) {
            _status.update { it.copy(state = RunState.STOPPED, targetFound = null) }
        }
    }

    /** 앱이 다시 열렸을 때 남아 있는 오류 표시를 지운다. */
    fun clearError() {
        _status.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // 실행 루프
    // ------------------------------------------------------------------

    private suspend fun runLoop(macro: Macro, settings: AppSettings) {
        val ctx = ExecutionContext(
            macro = macro,
            settings = settings,
            variables = variables,
            pausedFlow = paused,
            logger = ::appendLog,
        )

        startMonitor(macro, settings)

        val steps = macro.enabledSteps
        val repeat = macro.repeat
        var cycle = 0
        var successes = 0
        var failures = 0

        try {
            _status.update { it.copy(state = RunState.RUNNING) }
            appendLog("실행을 시작합니다. (단계 ${steps.size}개)")

            while (currentCoroutineContext().isActive) {
                if (!repeat.isInfinite && cycle >= repeat.count.coerceAtLeast(1)) {
                    finish("반복 ${cycle}회를 모두 마쳤습니다.", successes, failures)
                    return
                }
                cycle++
                variables.reset(macro.initialVariables)
                _status.update { it.copy(cycle = cycle, stepIndex = -1) }
                appendLog("===== ${cycle}번째 반복 시작 =====")

                when (val cycleResult = runCycle(ctx, steps)) {
                    is CycleResult.Completed -> {
                        successes++
                        _status.update { it.copy(successCount = successes) }
                        appendLog("${cycle}번째 반복 완료", RunLogEntry.Level.SUCCESS)
                        if (repeat.stopOnSuccess) {
                            finish("매크로를 정상적으로 마쳤습니다.", successes, failures)
                            return
                        }
                    }

                    is CycleResult.Restart -> {
                        failures++
                        _status.update { it.copy(failureCount = failures) }
                        appendLog("재시작: ${cycleResult.reason}", RunLogEntry.Level.WARN)
                        if (!repeat.restartOnFailure) {
                            fail(cycleResult.reason)
                            return
                        }
                    }

                    is CycleResult.Aborted -> {
                        failures++
                        fail(cycleResult.reason)
                        return
                    }

                    is CycleResult.Finished -> {
                        successes++
                        finish(cycleResult.reason, successes, failures)
                        return
                    }

                    is CycleResult.TargetHit -> {
                        successes++
                        _status.update { it.copy(successCount = successes) }
                        if (repeat.stopOnTargetFound) {
                            reportTargetFound(cycleResult.info)
                            return
                        }
                        // "타겟 발견 시 중지"를 꺼둔 경우: 기록만 남기고 계속 돈다.
                        appendLog(
                            "타겟 '${cycleResult.info.targetName}' 발견 " +
                                "(유사도 ${formatScore(cycleResult.info.score)}) - 설정에 따라 계속 진행합니다.",
                            RunLogEntry.Level.SUCCESS,
                        )
                    }
                }
                ctx.sleep(repeat.cycleDelayMs)
            }
        } catch (e: CancellationException) {
            // stop() 으로 인한 정상 취소.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "매크로 실행 중 예기치 못한 오류", e)
            fail("예기치 못한 오류로 매크로가 중단되었습니다: ${e.message ?: "알 수 없음"}")
        } finally {
            monitor.stop()
            files.pruneOldFiles(settings.logKeepCount)
        }
    }

    private sealed interface CycleResult {
        data object Completed : CycleResult
        data class Restart(val reason: String) : CycleResult
        data class Aborted(val reason: String) : CycleResult
        data class Finished(val reason: String) : CycleResult
        data class TargetHit(val info: TargetFoundInfo) : CycleResult
    }

    private suspend fun runCycle(
        ctx: ExecutionContext,
        steps: List<MacroStep>,
    ): CycleResult {
        steps.forEachIndexed { index, step ->
            if (!currentCoroutineContext().isActive) return CycleResult.Aborted("중지되었습니다.")

            // 화면 회전 등으로 해상도가 바뀌면 좌표 보정이 어긋난다.
            if (capture.screenSizeChanged()) {
                return CycleResult.Aborted(
                    "화면 크기가 바뀌었습니다. 화면 방향을 되돌리고 매크로를 다시 시작해주세요.",
                )
            }

            _status.update {
                it.copy(stepIndex = index, stepName = step.displayName(index))
            }

            when (val outcome = executor.execute(ctx, step, index)) {
                is StepOutcome.Continue -> Unit
                is StepOutcome.Restart -> return CycleResult.Restart(outcome.reason)
                is StepOutcome.Abort -> return CycleResult.Aborted(outcome.reason)
                is StepOutcome.Finish -> return CycleResult.Finished(outcome.reason)
                is StepOutcome.TargetHit -> return CycleResult.TargetHit(outcome.info)
            }
        }
        return CycleResult.Completed
    }

    private fun startMonitor(macro: Macro, settings: AppSettings) {
        if (!macro.targetSettings.monitorEnabled) return
        // 감시의 목적은 "발견 즉시 중지"이므로, 중지하지 않을 거면 켤 이유가 없다.
        if (!macro.repeat.stopOnTargetFound) {
            appendLog(
                "'타겟 발견 시 중지'가 꺼져 있어 실시간 타겟 감시를 시작하지 않습니다.",
                RunLogEntry.Level.WARN,
            )
            return
        }
        monitor.start(
            scope = scope,
            macro = macro,
            analysisScale = settings.analysisScale,
            shouldScan = {
                val state = _status.value.state
                state == RunState.RUNNING ||
                    (state == RunState.PAUSED && settings.monitorWhilePaused)
            },
            onFound = { info ->
                appendLog("타겟 감시가 '${info.targetName}' 을(를) 찾았습니다.", RunLogEntry.Level.SUCCESS)
                reportTargetFound(info)
                runJob?.cancel()
                runJob = null
            },
        )
    }

    /**
     * 타겟 발견 처리.
     *
     * 지금 하던 일을 즉시 멈추고, 추가 터치가 나가지 않게 일시정지 플래그를 세운 뒤
     * 사용자가 판단할 때까지 상태를 유지한다.
     */
    private fun reportTargetFound(info: TargetFoundInfo) {
        paused.value = true
        monitor.stop()
        _status.update {
            it.copy(
                state = RunState.TARGET_FOUND,
                targetFound = info,
                lastMatchScore = info.score,
            )
        }
        appendLog(
            "🎯 타겟 발견: ${info.targetName} (유사도 ${formatScore(info.score)}) - 매크로를 중지했습니다.",
            RunLogEntry.Level.SUCCESS,
        )
    }

    private fun finish(reason: String, successes: Int, failures: Int) {
        _status.update {
            it.copy(
                state = RunState.STOPPED,
                stepIndex = -1,
                successCount = successes,
                failureCount = failures,
            )
        }
        appendLog(reason, RunLogEntry.Level.SUCCESS)
    }

    private fun fail(reason: String) {
        _status.update { it.copy(state = RunState.ERROR, errorMessage = reason, stepIndex = -1) }
        appendLog(reason, RunLogEntry.Level.ERROR)
    }

    private fun appendLog(message: String, level: RunLogEntry.Level = RunLogEntry.Level.INFO) {
        _status.update {
            val entry = RunLogEntry(message = message, level = level)
            it.copy(log = (it.log + entry).takeLast(MAX_LOG_LINES))
        }
    }

    /** 유사도를 소수점 셋째 자리까지 보여준다. */
    private fun formatScore(score: Float): String =
        ((score * 1000).roundToInt() / 1000f).toString()

    private companion object {
        const val TAG = "MacroEngine"
        const val MAX_LOG_LINES = 300
    }
}
