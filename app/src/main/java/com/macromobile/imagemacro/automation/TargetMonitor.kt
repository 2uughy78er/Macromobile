package com.macromobile.imagemacro.automation

import android.util.Log
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.service.CaptureResult
import com.macromobile.imagemacro.service.ScreenCaptureManager
import com.macromobile.imagemacro.storage.TemplateFiles
import com.macromobile.imagemacro.vision.TargetDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 매크로 단계와 **별개로** 화면을 감시하다가 타겟이 보이면 즉시 중지를 요청한다.
 *
 * 매크로 흐름 어디에서 타겟이 나타나든 놓치지 않기 위한 장치다.
 * 오탐으로 매크로가 멈추는 것을 줄이려고 "연속 N회 검출" 옵션을 지원한다.
 * (`requiredConsecutiveMatches = 1` 이면 한 번만 보여도 즉시 중지한다.)
 */
class TargetMonitor(
    private val capture: ScreenCaptureManager,
    private val detector: TargetDetector,
    private val files: TemplateFiles,
) {
    private var job: Job? = null

    /**
     * 감시를 시작한다.
     *
     * @param shouldScan 지금 화면을 검사해도 되는지(일시정지 중 감시 여부 등)
     * @param onFound 타겟이 확정되면 호출된다. 이 콜백에서 매크로를 중지시킨다.
     */
    fun start(
        scope: CoroutineScope,
        macro: Macro,
        analysisScale: Float,
        shouldScan: () -> Boolean,
        onFound: suspend (TargetFoundInfo) -> Unit,
    ) {
        stop()
        val settings = macro.targetSettings
        if (!settings.monitorEnabled || macro.enabledTargets.isEmpty()) return

        val required = settings.requiredConsecutiveMatches.coerceAtLeast(1)
        val interval = settings.monitorIntervalMs.coerceAtLeast(100L)

        job = scope.launch {
            var consecutive = 0
            Log.i(TAG, "타겟 감시 시작 (간격 ${interval}ms, 연속 ${required}회)")
            while (isActive) {
                try {
                    if (!shouldScan()) {
                        consecutive = 0
                        delay(interval)
                        continue
                    }
                    val frame = when (val r = capture.captureFrame()) {
                        is CaptureResult.Ok -> r.frame
                        is CaptureResult.Error -> {
                            delay(interval)
                            continue
                        }
                    }
                    val result = detector.checkTargets(
                        frame = frame,
                        macro = macro,
                        settings = settings,
                        analysisScale = analysisScale,
                        stopAtFirstHit = true,
                    )
                    if (result.success) {
                        consecutive++
                        if (consecutive >= required) {
                            val primary = result.primary
                            val path = if (settings.saveScreenshotOnFound) {
                                files.saveScreenshot(frame.bitmap, "target_found")?.absolutePath
                            } else {
                                null
                            }
                            onFound(
                                TargetFoundInfo(
                                    targetName = primary?.templateName?.ifBlank { "이름 없는 타겟" }
                                        ?: "타겟",
                                    score = primary?.match?.score ?: result.bestScore,
                                    matches = result.matches,
                                    screenshotPath = path,
                                    screenWidth = frame.width,
                                    screenHeight = frame.height,
                                ),
                            )
                            return@launch
                        }
                        // 연속 검출을 확인하려면 짧게 다시 본다.
                        delay(minOf(interval, CONSECUTIVE_RECHECK_MS))
                        continue
                    } else {
                        consecutive = 0
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "타겟 감시 중 오류", e)
                    consecutive = 0
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = job?.isActive == true

    private companion object {
        const val TAG = "TargetMonitor"
        const val CONSECUTIVE_RECHECK_MS = 250L
    }
}
