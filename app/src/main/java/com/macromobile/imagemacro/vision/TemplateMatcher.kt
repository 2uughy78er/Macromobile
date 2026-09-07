package com.macromobile.imagemacro.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OpenCV 템플릿 매칭.
 *
 * 기존 PC 버전과 같은 규칙을 따른다.
 * - 알파 마스크가 있으면 `TM_CCORR_NORMED` + mask, 없으면 `TM_CCOEFF_NORMED`
 * - 템플릿을 만든 화면 해상도와 지금 화면 해상도가 다르면 템플릿을 리사이즈해 보정
 * - ROI 를 지정하면 그 영역만 잘라서 검사하고, 결과 좌표는 전체 화면 기준으로 되돌린다
 *
 * 이 클래스는 어떤 앱이나 게임도 알지 못한다. 넘겨받은 이미지 파일과 설정만으로 동작한다.
 */
class TemplateMatcher(private val cache: TemplateCache) {

    /**
     * 가장 잘 맞는 위치 하나를 찾는다.
     *
     * @param frame 검사할 화면
     * @param templateFile 템플릿 PNG 파일
     * @param templateRefWidth 템플릿을 캡처한 화면의 너비(0 이면 보정하지 않음)
     * @param templateRefHeight 템플릿을 캡처한 화면의 높이
     * @param threshold 이 값 이상이면 발견으로 본다
     * @param searchRect 검사할 화면 영역(실제 화면 픽셀). null 이면 전체
     * @param analysisScale 매칭 전에 화면을 줄이는 배율(1.0 = 원본)
     */
    fun find(
        frame: ScreenFrame,
        templateFile: File,
        templateRefWidth: Int,
        templateRefHeight: Int,
        threshold: Float,
        searchRect: ScreenRect? = null,
        analysisScale: Float = 1f,
    ): MatchResult {
        val prepared = prepare(
            frame, templateFile, templateRefWidth, templateRefHeight, searchRect, analysisScale,
        ) ?: return MatchResult.NONE

        return try {
            val result = matchRaw(prepared) ?: return MatchResult.NONE
            val mm = Core.minMaxLoc(result)
            result.release()
            prepared.toMatch(mm.maxVal.toFloat(), mm.maxLoc, threshold)
        } finally {
            prepared.releaseArea()
        }
    }

    /**
     * threshold 를 넘는 위치를 여러 개 찾는다. 겹치는 결과는 NMS 로 제거한다.
     *
     * 결과는 유사도 내림차순이다.
     */
    fun findAll(
        frame: ScreenFrame,
        templateFile: File,
        templateRefWidth: Int,
        templateRefHeight: Int,
        threshold: Float,
        searchRect: ScreenRect? = null,
        analysisScale: Float = 1f,
        maxResults: Int = 20,
    ): List<MatchResult> {
        val prepared = prepare(
            frame, templateFile, templateRefWidth, templateRefHeight, searchRect, analysisScale,
        ) ?: return emptyList()

        return try {
            val result = matchRaw(prepared) ?: return emptyList()
            val out = ArrayList<MatchResult>()
            val tw = prepared.template.width
            val th = prepared.template.height
            try {
                repeat(maxResults) {
                    val mm = Core.minMaxLoc(result)
                    val score = mm.maxVal.toFloat()
                    if (score < threshold || score.isNaN()) return@repeat
                    out += prepared.toMatch(score, mm.maxLoc, threshold)
                    suppress(result, mm.maxLoc, tw, th)
                }
            } finally {
                result.release()
            }
            out.filter { it.found }.sortedByDescending { it.score }.take(maxResults)
        } finally {
            prepared.releaseArea()
        }
    }

    // ------------------------------------------------------------------
    // 내부 구현
    // ------------------------------------------------------------------

    /** 매칭 직전 상태(잘라낸 화면 + 배율 보정된 템플릿 + 좌표 복원 정보). */
    private class Prepared(
        val area: Mat,
        val areaIsSubmat: Boolean,
        val template: LoadedTemplate,
        /** 잘라낸 영역의 좌상단 좌표(분석 배율이 적용된 좌표계). */
        val offsetX: Int,
        val offsetY: Int,
        /** 분석 배율. 결과 좌표를 이 값으로 나눠 실제 화면 좌표로 되돌린다. */
        val analysisScale: Float,
    ) {
        fun releaseArea() {
            if (areaIsSubmat) area.release()
        }

        fun toMatch(score: Float, loc: Point, threshold: Float): MatchResult {
            val inv = if (analysisScale > 0f) 1f / analysisScale else 1f
            val cx = (offsetX + loc.x + template.width / 2.0) * inv
            val cy = (offsetY + loc.y + template.height / 2.0) * inv
            val safeScore = if (score.isNaN() || score.isInfinite()) 0f else score
            return MatchResult(
                found = safeScore >= threshold,
                score = safeScore.coerceIn(0f, 1f),
                centerX = cx.roundToInt(),
                centerY = cy.roundToInt(),
                width = (template.width * inv).roundToInt(),
                height = (template.height * inv).roundToInt(),
            )
        }
    }

    private fun prepare(
        frame: ScreenFrame,
        templateFile: File,
        templateRefWidth: Int,
        templateRefHeight: Int,
        searchRect: ScreenRect?,
        analysisScale: Float,
    ): Prepared? {
        if (!templateFile.exists()) {
            Log.w(TAG, "템플릿 파일이 없습니다: ${templateFile.name}")
            return null
        }
        val scaleClamped = analysisScale.coerceIn(MIN_ANALYSIS_SCALE, 1f)

        // 템플릿 배율 = (캡처 당시 해상도 → 현재 화면) 보정 × 분석 축소 배율
        val fit = fitScale(templateRefWidth, templateRefHeight, frame.width, frame.height)
        val template = cache.scaled(templateFile, fit * scaleClamped) ?: return null
        if (template.width < 2 || template.height < 2) return null

        val screen = frame.rgbScaled(scaleClamped)

        var area = screen
        var isSubmat = false
        var offX = 0
        var offY = 0
        if (searchRect != null) {
            val x = (searchRect.x * scaleClamped).roundToInt().coerceIn(0, max(0, screen.cols() - 1))
            val y = (searchRect.y * scaleClamped).roundToInt().coerceIn(0, max(0, screen.rows() - 1))
            val w = (searchRect.width * scaleClamped).roundToInt().coerceAtLeast(1)
            val h = (searchRect.height * scaleClamped).roundToInt().coerceAtLeast(1)
            val right = min(screen.cols(), x + w)
            val bottom = min(screen.rows(), y + h)
            if (right - x < 2 || bottom - y < 2) return null
            area = Mat(screen, Rect(x, y, right - x, bottom - y))
            isSubmat = true
            offX = x
            offY = y
        }

        if (area.rows() < template.height || area.cols() < template.width) {
            // 템플릿이 검사 영역보다 크면 매칭할 수 없다.
            if (isSubmat) area.release()
            return null
        }
        return Prepared(area, isSubmat, template, offX, offY, scaleClamped)
    }

    private fun matchRaw(p: Prepared): Mat? {
        val result = Mat()
        return try {
            val mask = p.template.mask
            if (mask != null) {
                Imgproc.matchTemplate(p.area, p.template.image, result, Imgproc.TM_CCORR_NORMED, mask)
            } else {
                Imgproc.matchTemplate(p.area, p.template.image, result, Imgproc.TM_CCOEFF_NORMED)
            }
            // 마스크 매칭은 0 나누기로 NaN/Inf 가 생길 수 있다. 그대로 두면 minMaxLoc 이 엉뚱한 값을 준다.
            sanitize(result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "템플릿 매칭 실패", e)
            result.release()
            null
        }
    }

    /**
     * NaN 과 1 을 넘는 값을 0 으로 만든다.
     *
     * 마스크 매칭(`TM_CCORR_NORMED` + mask)은 마스크가 가린 영역에서 0 나누기가 일어나
     * NaN 이나 +Inf 를 낼 수 있다. 그대로 두면 `minMaxLoc` 이 그 위치를 최고점으로 뽑는다.
     */
    private fun sanitize(result: Mat) {
        try {
            Core.patchNaNs(result, 0.0)
            // 정규화된 유사도는 1 을 넘을 수 없다. 1 초과(= +Inf 포함)는 0 으로 만든다.
            Imgproc.threshold(result, result, 1.0, 0.0, Imgproc.THRESH_TOZERO_INV)
        } catch (e: Exception) {
            Log.w(TAG, "매칭 결과 정리 실패", e)
        }
    }

    /** 이미 찾은 위치 주변을 -1 로 덮어 다음 반복에서 다시 뽑히지 않게 한다(NMS). */
    private fun suppress(result: Mat, loc: Point, tw: Int, th: Int) {
        val x0 = max(0, (loc.x - tw / 2.0).roundToInt())
        val y0 = max(0, (loc.y - th / 2.0).roundToInt())
        val x1 = min(result.cols(), (loc.x + tw / 2.0).roundToInt() + 1)
        val y1 = min(result.rows(), (loc.y + th / 2.0).roundToInt() + 1)
        if (x1 - x0 < 1 || y1 - y0 < 1) return
        val region = Mat(result, Rect(x0, y0, x1 - x0, y1 - y0))
        region.setTo(Scalar(-1.0))
        region.release()
    }

    companion object {
        private const val TAG = "TemplateMatcher"
        const val MIN_ANALYSIS_SCALE = 0.25f

        /**
         * 템플릿을 캡처한 해상도 대비 현재 화면의 배율.
         *
         * letterbox 를 고려해 가로·세로 중 작은 쪽을 쓴다. 기준 해상도를 모르면 1 을 돌려준다.
         */
        fun fitScale(refWidth: Int, refHeight: Int, screenWidth: Int, screenHeight: Int): Float {
            if (refWidth <= 0 || refHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return 1f
            return min(
                screenWidth.toFloat() / refWidth,
                screenHeight.toFloat() / refHeight,
            )
        }
    }
}
