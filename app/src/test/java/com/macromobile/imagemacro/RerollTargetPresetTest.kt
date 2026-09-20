package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.preset.ImageTap
import com.macromobile.imagemacro.preset.PresetCard
import com.macromobile.imagemacro.preset.RerollFlow
import com.macromobile.imagemacro.preset.RerollTargetPreset
import com.macromobile.imagemacro.preset.Tap
import com.macromobile.imagemacro.preset.TargetCheck
import com.macromobile.imagemacro.preset.TextInput
import com.macromobile.imagemacro.preset.withPresetTargets
import com.macromobile.imagemacro.preset.withRerollFlowSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 리세 흐름과 목표카드 묶음의 값이 실측·지시에서 벗어나지 않게 못박는다.
 *
 * 숫자는 전부 실기기 캡처 50장(2304x1440)을 재서 나온 값이다. 누가 무심코 바꾸면
 * 엉뚱한 곳을 누르거나, 더 나쁘게는 목표카드를 뽑아놓고 계정을 초기화해버린다.
 */
class RerollTargetPresetTest {

    private val preset = RerollTargetPreset.COMPROSEPYA
    private val macro = Macro()
        .withPresetTargets(preset, now = 1L) { card: PresetCard -> card.assetFile }
        .withRerollFlowSteps(preset, now = 1L) { step: ImageTap -> step.assetFile }

    private val steps get() = macro.steps
    private fun indexOfStep(name: String) = steps.indexOfFirst { it.name == name }

    // ------------------------------------------------------------------
    // A. 기준 해상도
    // ------------------------------------------------------------------

    @Test
    fun `A 좌표와 ROI 가 2304x1440 기준이다`() {
        assertEquals(2304, RerollFlow.REFERENCE_WIDTH)
        assertEquals(1440, RerollFlow.REFERENCE_HEIGHT)
        assertEquals(2304, preset.referenceWidth)
        assertEquals(1440, preset.referenceHeight)
        assertEquals(2304, macro.referenceWidth)
        assertEquals(1440, macro.referenceHeight)
        assertTrue(macro.targets.all { it.referenceScreenWidth == 2304 })
        assertTrue(macro.templates.all { it.referenceScreenWidth == 2304 })
        assertTrue(macro.templates.all { it.referenceScreenHeight == 1440 })
    }

    @Test
    fun `A 좌표와 버튼 자리가 화면 안에 있다`() {
        RerollFlow.coordinateTaps.forEach { t ->
            assertTrue("${t.name} x=${t.x}", t.x in 0 until RerollFlow.REFERENCE_WIDTH)
            assertTrue("${t.name} y=${t.y}", t.y in 0 until RerollFlow.REFERENCE_HEIGHT)
        }
        RerollFlow.imageTaps.forEach { s ->
            assertTrue("${s.name} 은 폭이 있어야 한다", s.box.isValid)
            assertTrue("${s.name} 이 화면을 벗어난다", s.box.right <= RerollFlow.REFERENCE_WIDTH)
            assertTrue("${s.name} 이 화면을 벗어난다", s.box.bottom <= RerollFlow.REFERENCE_HEIGHT)
            assertTrue(s.box.x >= 0 && s.box.y >= 0)
        }
    }

    // ------------------------------------------------------------------
    // B. 이미지 터치 / 좌표 터치 구분
    // ------------------------------------------------------------------

    @Test
    fun `B 빨간 점으로 표시된 자리만 좌표 터치다`() {
        // 사용자가 '...터치' 로 이름 붙이고 빨간 점을 찍은 다섯 자리.
        val expected = listOf(
            "조합 재료 자리 1", "조합 재료 자리 2", "조합 재료 자리 3",
            "조합 결과 카드 자리 1", "조합 결과 카드 자리 2",
        )
        assertEquals(expected, RerollFlow.coordinateTaps.map { it.name })
        assertEquals(expected, steps.filter { it.type == ActionType.TAP }.map { it.name })
    }

    @Test
    fun `B 좌표 터치 단계는 이미지를 쓰지 않는다`() {
        steps.filter { it.type == ActionType.TAP }.forEach {
            assertNotNull("${it.name} 에 좌표가 있어야 한다", it.point)
            assertTrue("${it.name} 이 이미지를 쓰면 안 된다", it.templateIds.isEmpty())
        }
    }

    @Test
    fun `B 네모로 표시된 버튼은 전부 이미지로 찾는다`() {
        val imageTypes = setOf(ActionType.WAIT_AND_TAP, ActionType.TAP_IF_FOUND)
        val found = steps.filter { it.type in imageTypes }
        assertEquals(RerollFlow.imageTaps.size, found.size)
        found.forEach {
            assertEquals("${it.name} 에 이미지가 하나 있어야 한다", 1, it.templateIds.size)
            assertNull("${it.name} 은 좌표를 쓰면 안 된다", it.point)
        }
    }

    @Test
    fun `B 흐름 코드에 선수 이름 분기가 없다`() {
        val forbidden = listOf("오명진", "최지광", "박상원")
        val dir = listOf(
            File("src/main/java/com/macromobile/imagemacro/preset"),
            File("app/src/main/java/com/macromobile/imagemacro/preset"),
        ).first { it.isDirectory }
        val sources = dir.listFiles { f: File -> f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue("preset 소스를 찾지 못했습니다", sources.isNotEmpty())
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { name ->
                assertFalse(
                    "${file.name} 에 선수 이름 '$name' 으로 갈라지는 코드가 있으면 안 된다",
                    text.contains(name),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // C. 버튼을 제 자리에서만 찾는다
    // ------------------------------------------------------------------

    @Test
    fun `C 버튼마다 찾는 영역이 자기 자리로 좁혀져 있다`() {
        RerollFlow.imageTaps.forEach { s ->
            val r = s.searchRegion()
            assertTrue("${s.name}: 찾는 영역이 버튼을 감싸야 한다", r.x <= s.box.x)
            assertTrue(r.y <= s.box.y)
            assertTrue(r.right >= s.box.right)
            assertTrue(r.bottom >= s.box.bottom)
            // 화면 전체를 뒤지면 같은 모양의 [확인] 이 엉뚱하게 잡힌다.
            val area = r.width.toLong() * r.height
            val screen = RerollFlow.REFERENCE_WIDTH.toLong() * RerollFlow.REFERENCE_HEIGHT
            assertTrue("${s.name}: 찾는 영역이 너무 넓다", area < screen / 3)
            assertTrue(r.right <= RerollFlow.REFERENCE_WIDTH)
            assertTrue(r.bottom <= RerollFlow.REFERENCE_HEIGHT)
        }
    }

    @Test
    fun `C 같은 그림을 쓰는 단계도 찾는 영역은 각자 갖는다`() {
        // 대화상자 [확인] 은 여러 화면에 똑같이 생겼다. 단계마다 템플릿을 따로 만들어야
        // 영역을 다르게 줄 수 있다.
        val byFile = RerollFlow.imageTaps.groupBy { it.assetFile }
        assertTrue("같은 그림을 여러 단계가 쓰는 경우가 있어야 한다", byFile.any { it.value.size > 1 })
        assertEquals(RerollFlow.imageTaps.size, macro.templates.size)
        assertEquals(macro.templates.size, macro.templates.map { it.id }.toSet().size)
        macro.templates.forEach { assertNotNull("${it.name} 에 찾는 영역이 없다", it.searchRegion) }
    }

    @Test
    fun `C 단계가 템플릿의 영역을 그대로 쓴다`() {
        RerollFlow.imageTaps.forEach { s ->
            val step = steps.first { it.name == s.name && it.templateIds.isNotEmpty() }
            assertEquals("${s.name}: 단계 ROI", s.searchRegion(), step.roi)
        }
    }

    // ------------------------------------------------------------------
    // D. 검사 영역과 크기 제한
    // ------------------------------------------------------------------

    @Test
    fun `D ROI 가 조합 결과의 대형 카드를 덮고 작은 카드 위에서 끝난다`() {
        // 실측(b/10): 대형 카드 x 997~1310, y 194~677. 작은 카드는 y 740 부터.
        val roi = preset.roi
        assertTrue("ROI 가 카드 위쪽을 덮어야 한다", roi.y <= 194)
        assertTrue("ROI 가 카드 아래쪽을 덮어야 한다", roi.bottom >= 677)
        assertTrue("ROI 가 카드 왼쪽을 덮어야 한다", roi.x <= 997)
        assertTrue("ROI 가 카드 오른쪽을 덮어야 한다", roi.right >= 1310)
        assertTrue("ROI 아래끝(${roi.bottom})이 작은 카드 시작(740)보다 위여야 한다", roi.bottom < 740)
        assertTrue(roi.right <= preset.referenceWidth)
        assertTrue(roi.bottom <= preset.referenceHeight)
    }

    @Test
    fun `D 사용자가 표시한 영역이 ROI 안에 들어온다`() {
        // b/09 의 빨간 영역 (990,168,346,536)
        val roi = preset.roi
        assertTrue(roi.x <= 990 && roi.y <= 168)
        assertTrue(roi.right >= 990 + 346 && roi.bottom >= 168 + 536)
    }

    @Test
    fun `D 가장 큰 배율의 템플릿도 ROI 안에 들어간다`() {
        // 배율 훑기 최대 1.10. 템플릿 314x488 → 345x537.
        assertTrue("ROI 폭이 모자라다", preset.roi.width >= 345)
        assertTrue("ROI 높이가 모자라다", preset.roi.height >= 537)
    }

    @Test
    fun `D 크기 제한이 대형 카드는 통과시키고 작은 카드는 막는다`() {
        val big = 313f / preset.referenceWidth     // 실측 0.136
        val small = 156f / preset.referenceWidth   // 실측 0.068
        assertTrue("대형 카드가 하한을 넘어야 한다", big >= preset.minMatchWidthRatio)
        assertTrue("대형 카드가 상한 아래여야 한다", big <= preset.maxMatchWidthRatio)
        assertTrue("작은 카드는 하한에 걸려야 한다", small < preset.minMatchWidthRatio)
    }

    // ------------------------------------------------------------------
    // E. 판정 위치 - 스카우트 결과가 아니라 조합 결과
    // ------------------------------------------------------------------

    @Test
    fun `E 목표 판정이 조합하기 뒤에 온다`() {
        val combine = indexOfStep("조합하기")
        val check = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        assertTrue("'조합하기' 단계가 있어야 한다", combine >= 0)
        assertTrue("TARGET_CHECK 가 있어야 한다", check >= 0)
        assertTrue("판정이 조합 뒤여야 한다 (조합=$combine, 판정=$check)", check > combine)
    }

    @Test
    fun `E 스카우트 결과에서는 판정하지 않는다`() {
        val scoutDone = indexOfStep("스카우트 뒤로 가기")
        val firstCheck = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        assertTrue("스카우트 마무리 단계가 있어야 한다", scoutDone >= 0)
        assertTrue("스카우트 구간에 판정이 있으면 안 된다", firstCheck > scoutDone)
    }

    @Test
    fun `E 타겟 감시를 켜지 않는다`() {
        assertFalse(macro.targetSettings.monitorEnabled)
    }

    @Test
    fun `E 판정 전에 결과 화면이 떴는지 이미지로 확인한다`() {
        // 연출 길이를 짐작해서 몇 초 기다리는 대신, 결과 화면의 [확인] 이 보일 때까지 기다린다.
        val wait = indexOfStep("조합 결과 화면 기다리기")
        val check = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        assertTrue("결과 화면 대기 단계가 있어야 한다", wait >= 0)
        assertEquals(ActionType.WAIT_FOR_IMAGE, steps[wait].type)
        assertEquals("판정 바로 앞이어야 한다", check - 1, wait)
        // 결과 화면의 [확인] 버튼과 같은 이미지를 본다.
        val confirm = steps.first { it.name == "조합 결과 - 확인" }
        assertEquals(confirm.templateIds, steps[wait].templateIds)
    }

    @Test
    fun `E 판정 단계가 프리셋의 ROI 와 threshold 를 쓴다`() {
        val checks = steps.filter { it.type == ActionType.TARGET_CHECK }
        assertEquals(1, checks.size)
        checks.forEach {
            assertEquals(preset.roi, it.roi)
            assertEquals(preset.threshold, it.threshold!!, 0.0001f)
            // 목표가 없는 게 정상이다. 못 찾았다고 한 바퀴를 버리면 안 된다.
            assertEquals(OnTimeout.SKIP, it.onTargetMissing)
        }
    }

    // ------------------------------------------------------------------
    // F. 목표를 찾으면 그 아래는 실행되지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `F 확인과 초기화가 전부 판정 뒤에 있다`() {
        val check = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        listOf(
            "조합 결과 - 확인", "게임 초기화", "초기화 확인", "초기화 재확인",
            "초기화 확정", "초기화 완료 - 확인",
        ).forEach { name ->
            val at = indexOfStep(name)
            assertTrue("'$name' 단계가 있어야 한다", at >= 0)
            assertTrue("'$name'($at) 이 판정($check)보다 뒤여야 한다", at > check)
        }
    }

    @Test
    fun `F 판정 앞에는 되돌릴 수 없는 단계가 없다`() {
        val check = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        val destructive = setOf("조합 결과 - 확인", "조합 뒤로 가기", "게임 초기화")
        steps.take(check).forEach { step ->
            assertFalse(
                "판정 전에 '${step.name}' 이 실행되면 안 된다",
                step.name in destructive || step.name.startsWith("초기화"),
            )
        }
    }

    @Test
    fun `F 목표를 찾으면 매크로가 멈춘다`() {
        assertTrue("목표를 찾으면 멈춰야 한다", macro.repeat.stopOnTargetFound)
        assertTrue("찾을 때까지 계속 돌아야 한다", macro.repeat.isInfinite)
        assertFalse("한 바퀴 성공으로 멈추면 안 된다", macro.repeat.stopOnSuccess)
    }

    // ------------------------------------------------------------------
    // G. 초기화 문구
    // ------------------------------------------------------------------

    @Test
    fun `G 초기화 문구가 글자 그대로다`() {
        assertEquals("COM2US PROBASEBALL", RerollFlow.RESET_CONFIRM_TEXT)
        val input = steps.first { it.type == ActionType.TEXT_INPUT }
        assertEquals("COM2US PROBASEBALL", input.text)
        assertFalse("변수 치환으로 오해되면 안 된다", input.text.contains("@"))
        assertTrue("입력 전에 기존 내용을 지워야 한다", input.clearBeforeInput)
        assertEquals(OnTimeout.RESTART, input.onTimeout)
    }

    @Test
    fun `G 입력칸을 먼저 누르고 문구를 넣고 확정한다`() {
        val field = indexOfStep("초기화 문구 입력칸")
        val input = steps.indexOfFirst { it.type == ActionType.TEXT_INPUT }
        val confirm = indexOfStep("초기화 확정")
        assertTrue("입력칸 단계가 있어야 한다", field >= 0)
        assertEquals("입력칸 바로 뒤에 넣어야 한다", field + 1, input)
        assertEquals("문구 바로 뒤에 확정해야 한다", input + 1, confirm)
    }

    // ------------------------------------------------------------------
    // H. 목표카드 묶음
    // ------------------------------------------------------------------

    @Test
    fun `H 목표카드는 아홉 장이고 파일 이름이 겹치지 않는다`() {
        assertEquals(9, preset.cards.size)
        assertEquals(9, preset.cards.map { it.assetFile }.toSet().size)
        assertTrue(preset.cards.all { it.assetFile.endsWith(".png") })
        assertTrue(preset.cards.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `H threshold 가 교차 매칭과 자기 매칭 사이에 있다`() {
        // 실제 조합 결과 화면 실측: 목표 아닌 화면 0.459, 교차 0.666, 자기 0.904~0.948
        assertTrue("교차 매칭(0.666)보다 위여야 한다", preset.threshold > 0.666f)
        assertTrue("가장 약한 자기 매칭(0.904)보다 아래여야 한다", preset.threshold < 0.904f)
        assertEquals(0.82f, preset.threshold, 0.0001f)
    }

    @Test
    fun `H 연속 판정을 두 번 요구한다`() {
        assertEquals(2, preset.requiredConsecutiveMatches)
        assertEquals(2, macro.targetSettings.requiredConsecutiveMatches)
    }

    @Test
    fun `H 타겟이 프리셋 값을 그대로 물려받는다`() {
        assertEquals(9, macro.targets.size)
        macro.targets.forEach {
            assertEquals(preset.roi, it.searchRegion)
            assertEquals(preset.threshold, it.threshold, 0.0001f)
            assertEquals(preset.minMatchWidthRatio, it.minMatchWidthRatio, 0.0001f)
            assertEquals(preset.maxMatchWidthRatio, it.maxMatchWidthRatio, 0.0001f)
            assertTrue(it.enabled)
        }
        assertEquals(TargetMatchMode.ANY, macro.targetSettings.mode)
        assertTrue(macro.targetSettings.saveScreenshotOnFound)
    }

    @Test
    fun `H 이미지를 못 옮긴 항목은 단계와 타겟에서 빠진다`() {
        val applied = Macro()
            .withPresetTargets(preset, now = 1L) { card ->
                if (card.assetFile.startsWith("target_01")) null else card.assetFile
            }
            .withRerollFlowSteps(preset, now = 1L) { step ->
                if (step.name == "교체") null else step.assetFile
            }
        assertEquals(8, applied.targets.size)
        assertEquals(RerollFlow.imageTaps.size - 1, applied.templates.size)
        assertTrue(applied.steps.none { it.name == "교체" })
    }

    // ------------------------------------------------------------------
    // I. 한 바퀴 전체
    // ------------------------------------------------------------------

    @Test
    fun `I 단계 순서가 흐름 정의와 같다`() {
        val expected = RerollFlow.STEPS.flatMap {
            if (it is TargetCheck) listOf("조합 결과 화면 기다리기", it.name) else listOf(it.name)
        }
        assertEquals(expected, steps.map { it.name })
    }

    @Test
    fun `I 흐름이 팀 선택으로 시작해 초기화 완료로 끝난다`() {
        assertEquals("팀 선택 - 선택", steps.first().name)
        assertEquals("초기화 완료 - 확인", steps.last().name)
    }

    @Test
    fun `I 모든 단계 뒤에 2초를 쉰다`() {
        assertEquals(2_000L, RerollFlow.STEP_DELAY_MS)
        // 판정과 그 앞의 대기만 예외다. 둘은 곧바로 이어져야 해서 사이에 쉬지 않는다.
        val noDelay = setOf(ActionType.TARGET_CHECK, ActionType.WAIT_FOR_IMAGE)
        steps.filter { it.type !in noDelay }
            .forEach { assertEquals("'${it.name}' 의 대기", 2_000L, it.afterDelayMs) }
        steps.filter { it.type in noDelay }
            .forEach { assertEquals("'${it.name}' 은 바로 이어져야 한다", 0L, it.afterDelayMs) }
        assertEquals(2_000L, macro.targetSettings.settleDelayMs)
        assertEquals(2_000L, macro.repeat.cycleDelayMs)
    }

    @Test
    fun `I 없을 수도 있는 안내 팝업은 짧게 보고 넘어간다`() {
        val optional = steps.filter { it.type == ActionType.TAP_IF_FOUND }
        assertEquals(
            RerollTargetPreset.OPTIONAL_STEPS,
            optional.map { it.name }.toSet(),
        )
        optional.forEach { assertTrue("${it.name} 이 너무 오래 기다린다", it.timeoutMs <= 5_000L) }
    }

    @Test
    fun `I 연출이 긴 단계는 더 오래 기다린다`() {
        RerollTargetPreset.SLOW_STEPS.forEach { name ->
            val step = steps.firstOrNull { it.name == name }
            assertNotNull("'$name' 단계가 있어야 한다", step)
            assertEquals(
                "'$name' 은 오래 기다려야 한다",
                preset.slowFindTimeoutMs,
                step!!.timeoutMs,
            )
        }
        assertTrue(preset.slowFindTimeoutMs > preset.findTimeoutMs)
    }

    @Test
    fun `I 모든 단계가 켜져 있고 이름이 있다`() {
        assertTrue(steps.all { it.enabled })
        assertTrue(steps.all { it.name.isNotBlank() })
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
    }

    @Test
    fun `I 단계가 참조하는 이미지가 전부 매크로에 있다`() {
        val ids = macro.templates.map { it.id }.toSet()
        steps.forEach { step ->
            step.templateIds.forEach {
                assertTrue("'${step.name}' 이 없는 이미지를 가리킨다", it in ids)
            }
        }
    }

    @Test
    fun `I 프리셋을 아이디로 찾을 수 있다`() {
        assertEquals(preset, RerollTargetPreset.byId(RerollTargetPreset.ID))
        assertNull(RerollTargetPreset.byId("없는 아이디"))
        assertEquals(1, RerollTargetPreset.ALL.size)
    }

    @Test
    fun `I 이미 만든 단계를 타겟 적용이 지우지 않는다`() {
        val existing = Macro(steps = listOf(macro.steps.first()))
        val applied = existing.withPresetTargets(preset, now = 1L) { it.assetFile }
        assertEquals(1, applied.steps.size)
    }

    @Test
    fun `I 흐름에 좌표 다섯 개와 이미지 마흔여섯 개와 판정 하나와 입력 하나가 있다`() {
        assertEquals(5, RerollFlow.STEPS.count { it is Tap })
        assertEquals(46, RerollFlow.STEPS.count { it is ImageTap })
        assertEquals(1, RerollFlow.STEPS.count { it is TargetCheck })
        assertEquals(1, RerollFlow.STEPS.count { it is TextInput })
        assertEquals(RerollFlow.STEPS.size + 1, steps.size)   // +1 = 결과 화면 대기
    }
}
