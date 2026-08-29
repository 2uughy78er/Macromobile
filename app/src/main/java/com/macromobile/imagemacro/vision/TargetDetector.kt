package com.macromobile.imagemacro.vision

import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.model.TargetSettings
import com.macromobile.imagemacro.model.Template
import com.macromobile.imagemacro.model.TemplateMatchMode
import com.macromobile.imagemacro.storage.TemplateFiles

/** 여러 템플릿을 한 번에 판정한 결과. */
data class GroupMatchResult(
    val success: Boolean,
    val matches: List<NamedMatch>,
) {
    val hits: List<NamedMatch> get() = matches.filter { it.match.found }
    val best: NamedMatch? get() = matches.maxByOrNull { it.match.score }
    val bestScore: Float get() = best?.match?.score ?: 0f

    /** 클릭 대상으로 쓸 매칭. 성공한 것 중 유사도가 가장 높은 것. */
    val primary: NamedMatch? get() = hits.maxByOrNull { it.match.score }
}

/**
 * 매크로 모델(Template / Target)과 [TemplateMatcher] 를 이어주는 계층.
 *
 * 좌표 보정, threshold 기본값, ROI 우선순위 같은 "규칙"을 여기서 한 곳에 모아둔다.
 * 특정 앱이나 게임에 대한 지식은 전혀 들어가지 않는다.
 */
class TargetDetector(
    private val matcher: TemplateMatcher,
    private val files: TemplateFiles,
) {

    /** 템플릿 한 개를 찾는다. */
    fun findTemplate(
        frame: ScreenFrame,
        macro: Macro,
        template: Template,
        thresholdOverride: Float?,
        roiOverride: Roi?,
        analysisScale: Float,
    ): MatchResult {
        val file = files.templateFile(macro.id, template.fileName)
        val mapper = mapperFor(macro, frame)
        val roi = roiOverride ?: template.searchRegion
        return matcher.find(
            frame = frame,
            templateFile = file,
            templateRefWidth = template.referenceScreenWidth,
            templateRefHeight = template.referenceScreenHeight,
            threshold = thresholdOverride ?: template.threshold,
            searchRect = mapper.toScreenRect(roi),
            analysisScale = analysisScale,
        )
    }

    /** 같은 템플릿이 여러 곳에 있는 경우 전부 찾는다(NMS 적용). */
    fun findTemplateAll(
        frame: ScreenFrame,
        macro: Macro,
        template: Template,
        thresholdOverride: Float?,
        roiOverride: Roi?,
        analysisScale: Float,
        maxResults: Int = 20,
    ): List<MatchResult> {
        val file = files.templateFile(macro.id, template.fileName)
        val mapper = mapperFor(macro, frame)
        val roi = roiOverride ?: template.searchRegion
        return matcher.findAll(
            frame = frame,
            templateFile = file,
            templateRefWidth = template.referenceScreenWidth,
            templateRefHeight = template.referenceScreenHeight,
            threshold = thresholdOverride ?: template.threshold,
            searchRect = mapper.toScreenRect(roi),
            analysisScale = analysisScale,
            maxResults = maxResults,
        )
    }

    /**
     * 한 단계에 묶인 템플릿들을 [MacroStep.templateMatchMode] 규칙으로 판정한다.
     *
     * ANY 모드는 하나를 찾는 즉시 나머지 검사를 건너뛴다(속도).
     */
    fun findGroup(
        frame: ScreenFrame,
        macro: Macro,
        templateIds: List<String>,
        mode: TemplateMatchMode,
        minCount: Int,
        thresholdOverride: Float?,
        roiOverride: Roi?,
        analysisScale: Float,
    ): GroupMatchResult {
        val templates = templateIds.mapNotNull { macro.template(it) }.filter { it.enabled }
        if (templates.isEmpty()) return GroupMatchResult(false, emptyList())

        val results = ArrayList<NamedMatch>(templates.size)
        for (t in templates) {
            val m = findTemplate(frame, macro, t, thresholdOverride, roiOverride, analysisScale)
            results += NamedMatch(t.id, t.name, m)
            if (mode == TemplateMatchMode.ANY && m.found) break
        }
        val hitCount = results.count { it.match.found }
        val success = when (mode) {
            TemplateMatchMode.ANY -> hitCount >= 1
            TemplateMatchMode.ALL -> hitCount == templates.size
            TemplateMatchMode.COUNT -> hitCount >= minCount.coerceAtLeast(1)
        }
        return GroupMatchResult(success, results)
    }

    /**
     * 등록된 타겟들을 판정한다.
     *
     * @param targetIds 비어 있으면 매크로에 등록된 모든 (활성) 타겟을 검사한다.
     * @param modeOverride 단계에서 판정 방식을 덮어쓸 때 사용
     */
    fun checkTargets(
        frame: ScreenFrame,
        macro: Macro,
        settings: TargetSettings,
        targetIds: List<String> = emptyList(),
        modeOverride: TargetMatchMode? = null,
        minCountOverride: Int? = null,
        analysisScale: Float = 1f,
        stopAtFirstHit: Boolean = false,
    ): GroupMatchResult {
        val targets = if (targetIds.isEmpty()) {
            macro.enabledTargets
        } else {
            targetIds.mapNotNull { macro.target(it) }.filter { it.enabled }
        }
        if (targets.isEmpty()) return GroupMatchResult(false, emptyList())

        val mode = modeOverride ?: settings.mode
        val minCount = (minCountOverride ?: settings.minCount).coerceAtLeast(1)
        val mapper = mapperFor(macro, frame)

        val results = ArrayList<NamedMatch>(targets.size)
        for (t in targets) {
            val m = matchTarget(frame, macro, t, settings, mapper, analysisScale)
            results += NamedMatch(t.id, t.name, m)
            if (stopAtFirstHit && mode == TargetMatchMode.ANY && m.found) break
        }
        val hitCount = results.count { it.match.found }
        val success = when (mode) {
            TargetMatchMode.ANY -> hitCount >= 1
            TargetMatchMode.ALL -> hitCount == targets.size
            TargetMatchMode.COUNT -> hitCount >= minCount
        }
        return GroupMatchResult(success, results)
    }

    private fun matchTarget(
        frame: ScreenFrame,
        macro: Macro,
        target: Target,
        settings: TargetSettings,
        mapper: CoordinateMapper,
        analysisScale: Float,
    ): MatchResult {
        val file = files.targetFile(macro.id, target.fileName)
        val roi = target.searchRegion ?: settings.roi
        // 타겟 자체 threshold 가 기본값 그대로면 매크로 공통 설정을 따른다.
        val threshold = if (target.threshold == Target.DEFAULT_TARGET_THRESHOLD) {
            settings.threshold
        } else {
            target.threshold
        }
        val raw = matcher.find(
            frame = frame,
            templateFile = file,
            templateRefWidth = target.referenceScreenWidth,
            templateRefHeight = target.referenceScreenHeight,
            threshold = threshold,
            searchRect = mapper.toScreenRect(roi),
            analysisScale = analysisScale,
        )
        return applySizeGuard(raw, target, frame)
    }

    /**
     * 매칭 영역의 크기가 설정 범위를 벗어나면 오탐으로 보고 버린다.
     *
     * 기준은 "매칭 영역의 너비 ÷ 화면 너비". 화면 해상도가 달라도 같은 의미를 유지한다.
     * 예를 들어 화면 너비의 5% 보다 작은 매칭을 무시하려면 minMatchWidthRatio = 0.05.
     */
    private fun applySizeGuard(raw: MatchResult, target: Target, frame: ScreenFrame): MatchResult {
        if (!raw.found) return raw
        val minR = target.minMatchWidthRatio
        val maxR = target.maxMatchWidthRatio
        if (minR <= 0f && maxR <= 0f) return raw
        if (frame.width <= 0) return raw

        val ratio = raw.width.toFloat() / frame.width
        if (minR > 0f && ratio < minR) return raw.copy(found = false)
        if (maxR > 0f && ratio > maxR) return raw.copy(found = false)
        return raw
    }

    fun mapperFor(macro: Macro, frame: ScreenFrame): CoordinateMapper =
        CoordinateMapper(
            referenceWidth = if (macro.referenceWidth > 0) macro.referenceWidth else frame.width,
            referenceHeight = if (macro.referenceHeight > 0) macro.referenceHeight else frame.height,
            screenWidth = frame.width,
            screenHeight = frame.height,
        )
}
