package com.macromobile.imagemacro.automation

import android.graphics.Bitmap
import android.util.Log
import com.macromobile.imagemacro.input.GestureController
import com.macromobile.imagemacro.input.GestureOutcome
import com.macromobile.imagemacro.input.KeyEventController
import com.macromobile.imagemacro.input.TextInputController
import com.macromobile.imagemacro.input.TextInputOutcome
import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.ocr.OcrEngine
import com.macromobile.imagemacro.ocr.normalize
import com.macromobile.imagemacro.service.CaptureResult
import com.macromobile.imagemacro.service.ScreenCaptureManager
import com.macromobile.imagemacro.storage.TemplateFiles
import com.macromobile.imagemacro.vision.GroupMatchResult
import com.macromobile.imagemacro.vision.NamedMatch
import com.macromobile.imagemacro.vision.ScreenFrame
import com.macromobile.imagemacro.vision.TargetDetector
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 매크로 단계 하나를 실제로 실행한다.
 *
 * 이 클래스는 어떤 앱·게임·화면도 알지 못한다. 사용자가 만든 [MacroStep] 과
 * 사용자가 등록한 이미지만 보고 동작한다.
 */
class StepExecutor(
    private val capture: ScreenCaptureManager,
    private val detector: TargetDetector,
    private val gestures: GestureController,
    private val textInput: TextInputController,
    private val keys: KeyEventController,
    private val ocr: OcrEngine,
    private val files: TemplateFiles,
) {

    suspend fun execute(ctx: ExecutionContext, step: MacroStep, index: Int): StepOutcome {
        ctx.gate()
        val label = step.displayName(index)
        return try {
            when (step.type) {
                ActionType.WAIT -> doWait(ctx, step, label)
                ActionType.TAP -> doTap(ctx, step, label)
                ActionType.WAIT_FOR_IMAGE -> doWaitForImage(ctx, step, label, tapAfter = false)
                ActionType.WAIT_AND_TAP -> doWaitForImage(ctx, step, label, tapAfter = true)
                ActionType.TAP_IF_FOUND -> doTapIfFound(ctx, step, label)
                ActionType.TAP_UNTIL_IMAGE -> doTapUntil(ctx, step, label, untilAppears = true)
                ActionType.TAP_UNTIL_IMAGE_DISAPPEARS -> doTapUntil(ctx, step, label, untilAppears = false)
                ActionType.SWIPE -> doSwipe(ctx, step, label)
                ActionType.TEXT_INPUT -> doTextInput(ctx, step, label)
                ActionType.KEY_EVENT -> doKeyEvent(ctx, step, label)
                ActionType.SCREENSHOT -> doScreenshot(ctx, label)
                ActionType.OCR -> doOcr(ctx, step, label)
                ActionType.TARGET_CHECK -> doTargetCheck(ctx, step, label)
                ActionType.STOP_MACRO -> StepOutcome.Finish("'$label' 단계에서 매크로를 종료했습니다.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "단계 실행 중 메모리 부족", e)
            StepOutcome.Abort("메모리가 부족합니다. 설정에서 매칭 해상도를 낮춰보세요.")
        } catch (e: Exception) {
            Log.e(TAG, "단계 실행 실패: $label", e)
            StepOutcome.Abort("'$label' 단계에서 오류가 발생했습니다: ${friendly(e)}")
        } finally {
            // afterDelay 는 실패해도 지키는 편이 화면 전환에 안전하다.
            if (step.afterDelayMs > 0) {
                runCatching { ctx.sleep(step.afterDelayMs) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 개별 동작
    // ------------------------------------------------------------------

    private suspend fun doWait(ctx: ExecutionContext, step: MacroStep, label: String): StepOutcome {
        ctx.log("$label: ${step.waitMs}ms 대기")
        ctx.sleep(step.waitMs)
        return StepOutcome.Continue
    }

    private suspend fun doTap(ctx: ExecutionContext, step: MacroStep, label: String): StepOutcome {
        val point = step.point
            ?: return StepOutcome.Abort("'$label' 단계에 터치할 좌표가 없습니다. 단계를 편집해 좌표를 지정해주세요.")
        val (frame, error) = captureOnce(maxAgeMs = FRESH_ENOUGH_MS)
        if (frame == null) return StepOutcome.Abort("'$label' 단계: ${error ?: "화면을 가져오지 못했습니다."}")
        val mapper = detector.mapperFor(ctx.macro, frame)
        val (x, y) = mapper.toScreen(point)
        ctx.log("$label: (${point.x}, ${point.y}) 터치")
        return tapAt(ctx, x, y, label)
    }

    private suspend fun doWaitForImage(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
        tapAfter: Boolean,
    ): StepOutcome {
        if (step.templateIds.isEmpty()) {
            return StepOutcome.Abort("'$label' 단계에 등록된 이미지가 없습니다. 이미지를 먼저 등록해주세요.")
        }
        val search = waitForGroup(ctx, step, label, step.templateIds, step.timeoutMs)
        if (search.error != null) return StepOutcome.Abort(search.error)

        val result = search.result
        if (result == null || !result.success) {
            return onTimeout(ctx, step, label, result?.bestScore ?: 0f, search.lastFrame)
        }

        ctx.log(
            "$label: 이미지 발견 (유사도 ${format(result.bestScore)})",
            RunLogEntry.Level.SUCCESS,
        )
        if (!tapAfter) return StepOutcome.Continue

        val hit = result.primary ?: return StepOutcome.Continue
        val frame = search.lastFrame
            ?: return StepOutcome.Abort("'$label' 단계에서 화면 정보를 잃었습니다.")
        return tapMatch(ctx, step, hit, frame, label)
    }

    private suspend fun doTapIfFound(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
    ): StepOutcome {
        if (step.templateIds.isEmpty()) return StepOutcome.Continue
        // 나타날 수도, 안 나타날 수도 있는 팝업용. 짧게만 확인하고 없으면 그냥 넘어간다.
        val timeout = if (step.timeoutMs > 0) step.timeoutMs else DEFAULT_IF_FOUND_TIMEOUT_MS
        val search = waitForGroup(ctx, step, label, step.templateIds, timeout)
        if (search.error != null) return StepOutcome.Abort(search.error)

        val result = search.result
        if (result == null || !result.success) {
            ctx.log("$label: 이미지가 없어 건너뜁니다.")
            return StepOutcome.Continue
        }
        val hit = result.primary ?: return StepOutcome.Continue
        val frame = search.lastFrame ?: return StepOutcome.Continue
        ctx.log("$label: 이미지 발견 (유사도 ${format(result.bestScore)}) - 터치")
        return tapMatch(ctx, step, hit, frame, label)
    }

    private suspend fun doTapUntil(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
        untilAppears: Boolean,
    ): StepOutcome {
        val watchIds = if (untilAppears) {
            step.untilTemplateIds.ifEmpty { step.templateIds }
        } else {
            step.templateIds.ifEmpty { step.untilTemplateIds }
        }
        if (watchIds.isEmpty()) {
            return StepOutcome.Abort(
                "'$label' 단계에 확인할 이미지가 없습니다. 목표 이미지를 등록해주세요.",
            )
        }

        val deadline = System.currentTimeMillis() + step.timeoutMs
        val interval = step.repeatIntervalMs.coerceAtLeast(50L)
        var best = 0f
        ctx.log("$label: 연타 시작 (최대 ${step.timeoutMs / 1000}초)")

        while (System.currentTimeMillis() < deadline) {
            ctx.gate()
            val captured = capture.captureFrame()
            if (captured is CaptureResult.Error) {
                if (captured.fatal) return StepOutcome.Abort(captured.message)
                delay(interval)
                continue
            }
            val frame = (captured as CaptureResult.Ok).frame
            val group = detector.findGroup(
                frame = frame,
                macro = ctx.macro,
                templateIds = watchIds,
                mode = step.templateMatchMode,
                minCount = step.minMatchCount,
                thresholdOverride = step.threshold,
                roiOverride = step.roi,
                analysisScale = ctx.analysisScale,
            )
            best = maxOf(best, group.bestScore)

            val done = if (untilAppears) group.success else !group.success
            if (done) {
                val what = if (untilAppears) "목표 이미지 발견" else "대상 이미지 사라짐"
                ctx.log("$label: $what (유사도 ${format(group.bestScore)})", RunLogEntry.Level.SUCCESS)
                return StepOutcome.Continue
            }

            ctx.gate()
            val mapper = detector.mapperFor(ctx.macro, frame)
            val point = step.point
            if (point != null) {
                val (x, y) = mapper.toScreen(point)
                val outcome = tapAt(ctx, x, y, label)
                if (outcome !is StepOutcome.Continue) return outcome
            } else {
                return StepOutcome.Abort(
                    "'$label' 단계에 반복해서 터치할 좌표가 없습니다. 좌표를 지정해주세요.",
                )
            }
            delay(interval)
        }
        return onTimeout(ctx, step, label, best, capture.getLatestFrame())
    }

    private suspend fun doSwipe(ctx: ExecutionContext, step: MacroStep, label: String): StepOutcome {
        val start = step.swipeStart
        val end = step.swipeEnd
        if (start == null || end == null) {
            return StepOutcome.Abort("'$label' 단계에 스와이프 시작/끝 좌표가 없습니다.")
        }
        val (frame, error) = captureOnce(maxAgeMs = FRESH_ENOUGH_MS)
        if (frame == null) return StepOutcome.Abort("'$label' 단계: ${error ?: "화면을 가져오지 못했습니다."}")
        val mapper = detector.mapperFor(ctx.macro, frame)
        val (sx, sy) = mapper.toScreen(start)
        val (ex, ey) = mapper.toScreen(end)
        ctx.gate()
        ctx.log("$label: (${start.x}, ${start.y}) → (${end.x}, ${end.y}) 스와이프")
        return when (val r = gestures.swipe(sx, sy, ex, ey, step.swipeDurationMs)) {
            is GestureOutcome.Success -> StepOutcome.Continue
            is GestureOutcome.Failed -> StepOutcome.Abort(r.reason)
        }
    }

    private suspend fun doTextInput(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
    ): StepOutcome {
        val value = ctx.variables.resolve(step.text)
        if (value.isEmpty()) {
            ctx.log("$label: 입력할 내용이 비어 있어 건너뜁니다.", RunLogEntry.Level.WARN)
            return StepOutcome.Continue
        }
        ctx.gate()
        ctx.log("$label: 텍스트 입력 (${value.length}자)")
        return when (val r = textInput.input(value, step.clearBeforeInput)) {
            is TextInputOutcome.Success -> StepOutcome.Continue
            is TextInputOutcome.Failed -> applyPolicy(step.onTimeout, label, r.reason)
        }
    }

    private suspend fun doKeyEvent(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
    ): StepOutcome {
        ctx.gate()
        ctx.log("$label: ${step.keyAction.koreanLabel}")
        return when (val r = keys.perform(step.keyAction)) {
            is GestureOutcome.Success -> StepOutcome.Continue
            is GestureOutcome.Failed -> StepOutcome.Abort(r.reason)
        }
    }

    private suspend fun doScreenshot(ctx: ExecutionContext, label: String): StepOutcome {
        val (frame, error) = captureOnce()
        if (frame == null) return StepOutcome.Abort("'$label' 단계: ${error ?: "화면을 가져오지 못했습니다."}")
        val saved = files.saveScreenshot(frame.bitmap, "shot")
        if (saved == null) {
            ctx.log("$label: 화면 저장에 실패했습니다.", RunLogEntry.Level.WARN)
        } else {
            ctx.log("$label: 화면 저장 (${saved.name})", RunLogEntry.Level.SUCCESS)
        }
        return StepOutcome.Continue
    }

    private suspend fun doOcr(ctx: ExecutionContext, step: MacroStep, label: String): StepOutcome {
        val roi = step.roi
        if (roi == null || !roi.isValid) {
            return StepOutcome.Abort("'$label' 단계에 글자를 읽을 화면 영역(ROI)이 필요합니다.")
        }
        if (step.storeVariable.isBlank()) {
            return StepOutcome.Abort("'$label' 단계에 결과를 담을 변수 이름이 필요합니다.")
        }

        val deadline = System.currentTimeMillis() + step.timeoutMs
        val poll = ctx.pollInterval(step.pollIntervalMs)
        var bestText = ""

        while (System.currentTimeMillis() < deadline) {
            ctx.gate()
            val captured = capture.captureFrame()
            if (captured is CaptureResult.Error) {
                if (captured.fatal) return StepOutcome.Abort(captured.message)
                delay(poll)
                continue
            }
            val frame = (captured as CaptureResult.Ok).frame
            val mapper = detector.mapperFor(ctx.macro, frame)
            val rect = mapper.toScreenRect(roi)
                ?: return StepOutcome.Abort("'$label' 단계의 ROI 가 화면을 벗어났습니다.")

            val crop = cropBitmap(frame.bitmap, rect.x, rect.y, rect.width, rect.height)
            if (crop == null) {
                return StepOutcome.Abort("'$label' 단계의 ROI 영역을 잘라내지 못했습니다.")
            }
            val result = try {
                ocr.recognize(crop, step.ocrKorean)
            } finally {
                if (crop !== frame.bitmap) crop.recycle()
            }

            if (result.text.isNotBlank()) bestText = result.text
            val cleaned = result.normalize(step.charset, step.expectedLength)
            if (cleaned != null && result.confidence >= step.minConfidence) {
                ctx.variables.put(step.storeVariable, cleaned)
                ctx.log(
                    "$label: '$cleaned' 읽음 → 변수 @${step.storeVariable}",
                    RunLogEntry.Level.SUCCESS,
                )
                return StepOutcome.Continue
            }
            delay(poll)
        }

        saveDebugShot(ctx, "ocr_fail")
        val hint = if (bestText.isBlank()) "" else " (가장 근접한 결과: '$bestText')"
        return applyPolicy(step.onTimeout, label, "'$label' 단계에서 글자를 읽지 못했습니다.$hint")
    }

    private suspend fun doTargetCheck(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
    ): StepOutcome {
        val settings = ctx.macro.targetSettings
        if (ctx.macro.enabledTargets.isEmpty()) {
            return StepOutcome.Abort(
                "등록된 타겟 이미지가 없습니다. 매크로 편집에서 타겟 카드를 먼저 등록해주세요.",
            )
        }
        // 결과 화면 애니메이션이 끝나기를 기다린다.
        ctx.sleep(settings.settleDelayMs)
        ctx.gate()

        val (frame, error) = captureOnce()
        if (frame == null) return StepOutcome.Abort("'$label' 단계: ${error ?: "화면을 가져오지 못했습니다."}")
        val result = detector.checkTargets(
            frame = frame,
            macro = ctx.macro,
            settings = settings,
            targetIds = step.targetIds,
            modeOverride = step.targetMatchMode,
            minCountOverride = step.targetMinCount,
            analysisScale = ctx.analysisScale,
        )
        result.matches.forEach {
            ctx.log(
                "  판정 [${it.templateName.ifBlank { "이름 없음" }}] " +
                    "${format(it.match.score)} ${if (it.match.found) "일치" else "아님"}",
            )
        }

        if (!result.success) {
            saveDebugShot(ctx, "target_miss")
            return applyPolicy(step.onTargetMissing, label, "타겟을 찾지 못했습니다.")
        }
        return StepOutcome.TargetHit(buildTargetInfo(ctx, frame, result))
    }

    // ------------------------------------------------------------------
    // 공용 도우미
    // ------------------------------------------------------------------

    /**
     * 화면을 한 장 가져온다. 일시적인 실패는 잠깐 기다렸다 다시 시도한다.
     *
     * 화면이 멈춰 있으면 새 프레임이 안 나올 수 있는데, 그건 오류가 아니라 그냥
     * "화면이 그대로"라는 뜻이다. 권한이 끊긴 경우에만 매크로를 멈춘다.
     *
     * @return (프레임, 실패 사유). 성공하면 사유가 null, 실패하면 프레임이 null 이다.
     */
    private suspend fun captureOnce(maxAgeMs: Long = 0L): Pair<ScreenFrame?, String?> {
        var lastMessage = "화면을 가져오지 못했습니다."
        repeat(SINGLE_CAPTURE_ATTEMPTS) {
            val captured = capture.captureFrame(maxAgeMs)
            if (captured is CaptureResult.Ok) return captured.frame to null
            val error = captured as CaptureResult.Error
            if (error.fatal) return null to error.message
            lastMessage = error.message
            delay(SINGLE_CAPTURE_RETRY_MS)
        }
        return null to lastMessage
    }

    /** 이미지 검색 결과와, 마지막으로 본 화면을 함께 담는다. */
    private data class SearchOutcome(
        val result: GroupMatchResult?,
        val lastFrame: ScreenFrame?,
        val error: String? = null,
    )

    /** [timeoutMs] 안에 템플릿 그룹이 조건을 만족할 때까지 화면을 반복 검사한다. */
    private suspend fun waitForGroup(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
        templateIds: List<String>,
        timeoutMs: Long,
    ): SearchOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        val poll = ctx.pollInterval(step.pollIntervalMs)
        var best: GroupMatchResult? = null
        var lastFrame: ScreenFrame? = null
        var matchedFrame: ScreenFrame? = null

        while (true) {
            ctx.gate()
            val captured = capture.captureFrame()
            if (captured is CaptureResult.Error) {
                // 권한이 끊긴 게 아니라면 잠시 뒤 다시 본다. 시간 안에 못 찾으면 timeout 정책을 따른다.
                if (captured.fatal) return SearchOutcome(best, lastFrame, captured.message)
                if (System.currentTimeMillis() >= deadline) break
                delay(poll)
                continue
            }
            val frame = (captured as CaptureResult.Ok).frame
            lastFrame = frame

            // 화면이 그대로면 같은 프레임이 다시 온다. 결과가 같으므로 매칭을 건너뛴다.
            if (frame === matchedFrame) {
                if (System.currentTimeMillis() >= deadline) break
                delay(poll)
                continue
            }
            matchedFrame = frame

            val group = detector.findGroup(
                frame = frame,
                macro = ctx.macro,
                templateIds = templateIds,
                mode = step.templateMatchMode,
                minCount = step.minMatchCount,
                thresholdOverride = step.threshold,
                roiOverride = step.roi,
                analysisScale = ctx.analysisScale,
            )
            val previousBest = best
            if (previousBest == null || group.bestScore > previousBest.bestScore) best = group
            if (group.success) return SearchOutcome(group, frame)
            if (System.currentTimeMillis() >= deadline) break
            delay(poll)
        }

        return SearchOutcome(best, lastFrame)
    }

    /** 매칭 위치 + 오프셋을 실제 화면 좌표로 바꿔 터치한다. */
    private suspend fun tapMatch(
        ctx: ExecutionContext,
        step: MacroStep,
        hit: NamedMatch,
        frame: ScreenFrame,
        label: String,
    ): StepOutcome {
        val mapper = detector.mapperFor(ctx.macro, frame)
        val template = ctx.macro.template(hit.templateId)
        val offsetX = if (step.useTemplateClickOffset && template != null) {
            template.clickOffsetX + step.clickOffsetX
        } else {
            step.clickOffsetX
        }
        val offsetY = if (step.useTemplateClickOffset && template != null) {
            template.clickOffsetY + step.clickOffsetY
        } else {
            step.clickOffsetY
        }
        // 오프셋은 기준 해상도 기준의 "거리"이므로 배율만 곱한다(가운데 여백은 더하지 않는다).
        val x = hit.match.centerX + mapper.scaleLength(offsetX)
        val y = hit.match.centerY + mapper.scaleLength(offsetY)
        ctx.gate()
        return tapAt(ctx, x, y, label)
    }

    private suspend fun tapAt(
        ctx: ExecutionContext,
        x: Int,
        y: Int,
        label: String,
    ): StepOutcome {
        gestures.jitterPx = ctx.settings.tapJitterPx
        return when (val r = gestures.tap(x, y)) {
            is GestureOutcome.Success -> StepOutcome.Continue
            is GestureOutcome.Failed -> StepOutcome.Abort("'$label' 단계: ${r.reason}")
        }
    }

    private suspend fun onTimeout(
        ctx: ExecutionContext,
        step: MacroStep,
        label: String,
        bestScore: Float,
        frame: ScreenFrame?,
    ): StepOutcome {
        if (ctx.settings.saveDebugScreenshots && frame != null) {
            files.saveScreenshot(frame.bitmap, "timeout_${sanitize(label)}", intoLogs = true)
        }
        val reason = "'$label' 단계에서 이미지를 시간 안에 찾지 못했습니다. " +
            "(가장 높은 유사도 ${format(bestScore)})"
        return applyPolicy(step.onTimeout, label, reason)
    }

    private fun applyPolicy(policy: OnTimeout, label: String, reason: String): StepOutcome =
        when (policy) {
            OnTimeout.SKIP -> StepOutcome.Continue
            OnTimeout.RESTART -> StepOutcome.Restart(reason)
            OnTimeout.STOP -> StepOutcome.Abort(reason)
        }

    private fun saveDebugShot(ctx: ExecutionContext, prefix: String) {
        if (!ctx.settings.saveDebugScreenshots) return
        capture.getLatestFrame()?.let { files.saveScreenshot(it.bitmap, prefix, intoLogs = true) }
    }

    private fun buildTargetInfo(
        ctx: ExecutionContext,
        frame: ScreenFrame,
        result: GroupMatchResult,
    ): TargetFoundInfo {
        val primary = result.primary
        val path = if (ctx.macro.targetSettings.saveScreenshotOnFound) {
            files.saveScreenshot(frame.bitmap, "target_found")?.absolutePath
        } else {
            null
        }
        return TargetFoundInfo(
            targetName = primary?.templateName?.ifBlank { "이름 없는 타겟" } ?: "타겟",
            score = primary?.match?.score ?: result.bestScore,
            matches = result.matches,
            screenshotPath = path,
            screenWidth = frame.width,
            screenHeight = frame.height,
        )
    }

    private fun cropBitmap(source: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? = try {
        val safeX = x.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val safeW = w.coerceIn(1, source.width - safeX)
        val safeH = h.coerceIn(1, source.height - safeY)
        Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
    } catch (e: Exception) {
        Log.e(TAG, "ROI 잘라내기 실패", e)
        null
    }

    private fun friendly(e: Exception): String = when (e) {
        is SecurityException -> "권한이 만료되었습니다. 화면 캡처 권한을 다시 허용해주세요."
        is IllegalStateException -> "서비스 상태가 올바르지 않습니다. 매크로를 다시 시작해주세요."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9가-힣]"), "_").take(24)

    private fun format(score: Float) = ((score * 1000).roundToInt() / 1000f).toString()

    private companion object {
        const val TAG = "StepExecutor"

        /** 좌표 터치처럼 최신 화면이 꼭 필요하지 않을 때 재사용할 프레임 나이. */
        const val FRESH_ENOUGH_MS = 400L
        const val DEFAULT_IF_FOUND_TIMEOUT_MS = 2_000L

        /** 단발 캡처가 일시적으로 실패했을 때의 재시도 횟수와 간격. */
        const val SINGLE_CAPTURE_ATTEMPTS = 6
        const val SINGLE_CAPTURE_RETRY_MS = 150L
    }
}
