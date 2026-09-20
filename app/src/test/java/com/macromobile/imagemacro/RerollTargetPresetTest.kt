package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.preset.PresetCard
import com.macromobile.imagemacro.preset.RerollTargetPreset
import com.macromobile.imagemacro.preset.withPresetTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목표카드 묶음의 값이 실측에서 벗어나지 않게 못박는다.
 *
 * 여기 숫자들은 제공된 결과 화면과 카드 9장을 재서 나온 값이다. 누가 무심코 바꾸면
 * 작은 카드에서 멈추거나 목표카드를 놓치게 되므로 시험으로 고정한다.
 */
class RerollTargetPresetTest {

    private val preset = RerollTargetPreset.COMPROSEPYA

    @Test
    fun `목표카드는 아홉 장이고 파일 이름이 겹치지 않는다`() {
        assertEquals(9, preset.cards.size)
        assertEquals(9, preset.cards.map { it.assetFile }.toSet().size)
        assertTrue(preset.cards.all { it.assetFile.endsWith(".png") })
        assertTrue(preset.cards.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `검사 영역이 작은 카드 위에서 끝난다`() {
        // 실측: 확대 카드 y 128~452, 작은 카드는 y 503 부터 시작한다.
        val roi = preset.roi
        assertTrue("ROI 가 확대 카드 위쪽을 덮어야 한다", roi.y <= 128)
        assertTrue("ROI 가 확대 카드 아래쪽을 덮어야 한다", roi.bottom >= 452)
        assertTrue(
            "ROI 아래끝(${roi.bottom})이 작은 카드 시작(503)보다 위여야 한다",
            roi.bottom < 503,
        )
    }

    @Test
    fun `검사 영역이 확대 카드를 가로로도 덮는다`() {
        val roi = preset.roi
        assertTrue(roi.x <= 664)
        assertTrue(roi.right >= 872)
        assertTrue("ROI 가 화면을 벗어나면 안 된다", roi.right <= preset.referenceWidth)
        assertTrue(roi.bottom <= preset.referenceHeight)
    }

    @Test
    fun `크기 제한이 확대 카드는 통과시키고 작은 카드는 막는다`() {
        val big = 209f / preset.referenceWidth     // 확대 카드 실측
        val small = 110f / preset.referenceWidth   // 작은 카드 실측
        assertTrue("확대 카드가 하한을 넘어야 한다", big >= preset.minMatchWidthRatio)
        assertTrue("확대 카드가 상한 안이어야 한다", big <= preset.maxMatchWidthRatio)
        assertTrue("작은 카드는 하한에 걸려야 한다", small < preset.minMatchWidthRatio)
    }

    @Test
    fun `배율을 훑어도 크기 제한 안에 머문다`() {
        // CARD_SCALE_SWEEP 의 양 끝(0.90 / 1.10)에서 매칭돼도 통과해야 한다.
        val w = 209f / preset.referenceWidth
        assertTrue(w * 0.90f >= preset.minMatchWidthRatio)
        assertTrue(w * 1.10f <= preset.maxMatchWidthRatio)
    }

    @Test
    fun `threshold 가 교차 매칭 최대와 자기 매칭 사이에 있다`() {
        // 실측: 교차 매칭 최대 0.686, 배율 훑기 시 자기 매칭 1.000
        assertTrue("다른 카드(0.686)보다 높아야 한다", preset.threshold > 0.70f)
        assertTrue("자기 매칭을 놓칠 만큼 높으면 안 된다", preset.threshold < 0.95f)
    }

    @Test
    fun `연속 일치를 요구해 연출 중간 프레임을 거른다`() {
        assertTrue(preset.requiredConsecutiveMatches >= 2)
        // 그래도 빨리 멈춰야 하므로 검사 간격은 짧게.
        assertTrue(preset.monitorIntervalMs in 100L..200L)
    }

    @Test
    fun `매크로에 얹으면 타겟이 만들어지고 설정이 따라온다`() {
        val macro = Macro(name = "리세")
        val applied = macro.withPresetTargets(preset, now = 1_000L) { it.assetFile }

        assertEquals(9, applied.targets.size)
        assertEquals(preset.referenceWidth, applied.referenceWidth)
        assertEquals(TargetMatchMode.ANY, applied.targetSettings.mode)
        assertEquals(preset.roi, applied.targetSettings.roi)
        assertEquals(2, applied.targetSettings.requiredConsecutiveMatches)
        assertTrue(applied.targetSettings.monitorEnabled)
        assertTrue(applied.targetSettings.saveScreenshotOnFound)

        val first = applied.targets.first()
        assertEquals(preset.roi, first.searchRegion)
        assertEquals(preset.referenceWidth, first.referenceScreenWidth)
        assertEquals(preset.threshold, first.threshold, 0.0001f)
    }

    @Test
    fun `이미지를 옮기지 못한 카드는 타겟으로 만들지 않는다`() {
        // 이미지 없는 타겟은 언제나 매칭에 실패하면서 이유가 보이지 않는다. 아예 뺀다.
        val applied = Macro(name = "리세").withPresetTargets(preset, now = 1L) { card ->
            if (card.assetFile.contains("target_01")) null else card.assetFile
        }
        assertEquals(8, applied.targets.size)
        assertFalse(applied.targets.any { it.fileName.contains("target_01") })
    }

    @Test
    fun `기존 매크로의 기준 해상도는 덮어쓰지 않는다`() {
        val macro = Macro(name = "리세", referenceWidth = 2400, referenceHeight = 1080)
        val applied = macro.withPresetTargets(preset, now = 1L) { it.assetFile }
        assertEquals(2400, applied.referenceWidth)
        assertEquals(1080, applied.referenceHeight)
    }

    @Test
    fun `단계는 건드리지 않는다`() {
        val macro = Macro(
            name = "리세",
            steps = listOf(com.macromobile.imagemacro.model.MacroStep(name = "뽑기 버튼")),
        )
        val applied = macro.withPresetTargets(preset, now = 1L) { it.assetFile }
        assertEquals(1, applied.steps.size)
        assertEquals("뽑기 버튼", applied.steps.first().name)
    }

    @Test
    fun `id 로 찾을 수 있다`() {
        assertNotNull(RerollTargetPreset.byId(RerollTargetPreset.ID))
        assertEquals(null, RerollTargetPreset.byId("없는묶음"))
    }

    @Test
    fun `카드 이름이 사람이 알아볼 수 있게 들어 있다`() {
        val names = preset.cards.joinToString(" ") { it.displayName }
        listOf("조성환", "문보경", "김영웅", "김동주B", "김도영", "문동주", "구자욱", "이정후")
            .forEach { assertTrue("$it 이름이 있어야 한다", names.contains(it)) }
    }

    @Test
    fun `자산 경로가 실제 폴더와 맞는다`() {
        assertEquals("presets/comprosepya_reroll/targets", preset.assetDir)
    }

    @Test
    fun `카드 하나짜리 묶음도 얹을 수 있다`() {
        val one = preset.copy(cards = listOf(PresetCard("a.png", "카드 하나")))
        val applied = Macro().withPresetTargets(one, now = 1L) { it.assetFile }
        assertEquals(1, applied.targets.size)
        assertEquals("카드 하나", applied.targets.first().name)
    }
}
