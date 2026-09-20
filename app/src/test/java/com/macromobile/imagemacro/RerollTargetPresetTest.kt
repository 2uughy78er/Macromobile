package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.preset.PresetCard
import com.macromobile.imagemacro.preset.RerollFlow
import com.macromobile.imagemacro.preset.RerollTargetPreset
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
 * 여기 숫자들은 실기기 캡처(2304×1440)를 재서 나온 값이다. 누가 무심코 바꾸면
 * 작은 카드에서 멈추거나, 더 나쁘게는 목표카드를 뽑아놓고 계정을 초기화해버린다.
 */
class RerollTargetPresetTest {

    private val preset = RerollTargetPreset.COMPROSEPYA
    private val macro = Macro()
        .withPresetTargets(preset, now = 1L) { card: PresetCard -> card.assetFile }
        .withRerollFlowSteps(preset, now = 1L)

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
        // 모든 타겟이 같은 기준을 쓴다. 섞이면 CoordinateMapper 가 엉뚱하게 옮긴다.
        assertTrue(macro.targets.all { it.referenceScreenWidth == 2304 })
        assertTrue(macro.targets.all { it.referenceScreenHeight == 1440 })
    }

    @Test
    fun `A 모든 좌표가 화면 안에 있다`() {
        RerollFlow.allTaps.forEach { tap ->
            assertTrue(
                "${tap.name} x=${tap.x} 가 화면을 벗어난다",
                tap.x in 0 until RerollFlow.REFERENCE_WIDTH,
            )
            assertTrue(
                "${tap.name} y=${tap.y} 가 화면을 벗어난다",
                tap.y in 0 until RerollFlow.REFERENCE_HEIGHT,
            )
        }
    }

    // ------------------------------------------------------------------
    // B. 선수 이름이 아니라 좌표로 고른다
    // ------------------------------------------------------------------

    @Test
    fun `B 카드 선택 단계가 전부 좌표 터치다`() {
        val picks = listOf(
            "라인업 카드 자리", "교체할 카드 자리",
            "조합 재료 자리 1", "조합 재료 자리 2", "조합 재료 자리 3",
            "결과 카드 자리 1", "결과 카드 자리 2",
        )
        picks.forEach { name ->
            val step = steps.firstOrNull { it.name == name }
            assertNotNull("'$name' 단계가 있어야 한다", step)
            assertEquals("'$name' 은 좌표로 눌러야 한다", ActionType.TAP, step!!.type)
            assertNotNull("'$name' 에 좌표가 있어야 한다", step.point)
            // 이미지로 고르면 매 리세마다 다른 선수가 떠서 맞출 수 없다.
            assertTrue("'$name' 이 이미지를 쓰면 안 된다", step.templateIds.isEmpty())
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

    @Test
    fun `B 단계 이름이 선수가 아니라 자리를 가리킨다`() {
        val pickSteps = steps.filter { it.name.contains("자리") }
        // 자리로 고르는 단계가 실제로 존재해야 한다.
        assertTrue(pickSteps.size >= 7)
        assertTrue(pickSteps.all { it.type == ActionType.TAP && it.point != null })
    }

    // ------------------------------------------------------------------
    // C. 검사 영역
    // ------------------------------------------------------------------

    @Test
    fun `C ROI 가 지시받은 값 그대로다`() {
        assertEquals(966, preset.roi.x)
        assertEquals(153, preset.roi.y)
        assertEquals(374, preset.roi.width)
        assertEquals(566, preset.roi.height)
    }

    @Test
    fun `C 검사 영역이 확대 카드를 덮고 작은 카드 위에서 끝난다`() {
        // 실측: 확대 카드 x 994~1308, y 193~679. 작은 카드는 y 760 부터 시작한다.
        val roi = preset.roi
        assertTrue("ROI 가 확대 카드 위쪽을 덮어야 한다", roi.y <= 193)
        assertTrue("ROI 가 확대 카드 아래쪽을 덮어야 한다", roi.bottom >= 679)
        assertTrue("ROI 가 확대 카드 왼쪽을 덮어야 한다", roi.x <= 994)
        assertTrue("ROI 가 확대 카드 오른쪽을 덮어야 한다", roi.right >= 1308)
        assertTrue(
            "ROI 아래끝(${roi.bottom})이 작은 카드 시작(760)보다 위여야 한다",
            roi.bottom < 760,
        )
        assertTrue("ROI 가 화면을 벗어나면 안 된다", roi.right <= preset.referenceWidth)
        assertTrue(roi.bottom <= preset.referenceHeight)
    }

    @Test
    fun `C 크기 제한이 확대 카드는 통과시키고 작은 카드는 막는다`() {
        val big = 314f / preset.referenceWidth     // 확대 카드 실측 0.136
        val small = 166f / preset.referenceWidth   // 작은 카드 실측 0.072
        assertTrue("확대 카드가 하한을 넘어야 한다", big >= preset.minMatchWidthRatio)
        assertTrue("확대 카드가 상한 아래여야 한다", big <= preset.maxMatchWidthRatio)
        assertTrue("작은 카드는 하한에 걸려야 한다", small < preset.minMatchWidthRatio)
    }

    // ------------------------------------------------------------------
    // D. 대기 시간
    // ------------------------------------------------------------------

    @Test
    fun `D 모든 대기가 2초다`() {
        assertEquals(2_000L, RerollFlow.STEP_DELAY_MS)
        steps.filter { it.type == ActionType.TAP || it.type == ActionType.TEXT_INPUT }
            .forEach {
                assertEquals("'${it.name}' 의 대기가 2초가 아니다", 2_000L, it.afterDelayMs)
            }
        assertEquals(2_000L, macro.targetSettings.settleDelayMs)
        assertEquals(2_000L, macro.repeat.cycleDelayMs)
    }

    // ------------------------------------------------------------------
    // E. 판정 위치 - 스카우트 결과가 아니라 조합 결과
    // ------------------------------------------------------------------

    @Test
    fun `E 목표 판정이 조합 뒤에 온다`() {
        val combine = indexOfStep("조합하기")
        val check = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        assertTrue("'조합하기' 단계가 있어야 한다", combine >= 0)
        assertTrue("TARGET_CHECK 가 있어야 한다", check >= 0)
        assertTrue("판정이 조합 뒤여야 한다 (조합=$combine, 판정=$check)", check > combine)
    }

    @Test
    fun `E 스카우트 결과에서는 판정하지 않는다`() {
        val scoutDone = indexOfStep("뒤로가기")   // SCOUT 의 마지막 단계
        val firstCheck = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        assertTrue("스카우트 마무리 단계가 있어야 한다", scoutDone >= 0)
        assertTrue(
            "스카우트 결과 구간(0..$scoutDone)에 판정이 있으면 안 된다",
            firstCheck > scoutDone,
        )
    }

    @Test
    fun `E 타겟 감시를 켜지 않는다`() {
        // 감시를 켜면 흐름 어디서든 멈춰서 판정 위치를 조합 결과 한 곳으로 묶을 수 없다.
        assertFalse(macro.targetSettings.monitorEnabled)
    }

    @Test
    fun `E 판정 단계가 프리셋의 ROI 와 threshold 를 쓴다`() {
        val checks = steps.filter { it.type == ActionType.TARGET_CHECK }
        assertTrue(checks.isNotEmpty())
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
        val lastCheck = steps.indexOfLast { it.type == ActionType.TARGET_CHECK }
        listOf("선택하기", "결과 확인", "게임 초기화", "초기화 확인", "초기화 확정").forEach { name ->
            val at = indexOfStep(name)
            assertTrue("'$name' 단계가 있어야 한다", at >= 0)
            assertTrue(
                "'$name'($at) 이 판정($lastCheck)보다 뒤여야 목표를 찾았을 때 실행되지 않는다",
                at > lastCheck,
            )
        }
    }

    @Test
    fun `F 판정 앞에는 되돌릴 수 없는 단계가 없다`() {
        val firstCheck = steps.indexOfFirst { it.type == ActionType.TARGET_CHECK }
        // 조합 결과를 확정하거나 계정을 지우는 단계. 판정 전에 오면 목표카드를 잃는다.
        val destructive = setOf("선택하기", "결과 확인", "조합 화면 뒤로가기")
        steps.take(firstCheck).forEach { step ->
            assertFalse(
                "판정 전에 '${step.name}' 이 실행되면 안 된다",
                step.name in destructive || step.name.contains("초기화"),
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
        // 변수 치환(@이름)으로 오해되면 안 된다.
        assertFalse(input.text.contains("@"))
        assertTrue("입력 전에 기존 내용을 지워야 한다", input.clearBeforeInput)
    }

    @Test
    fun `G 입력칸을 먼저 누르고 문구를 넣는다`() {
        val field = indexOfStep("초기화 문구 입력칸")
        val input = steps.indexOfFirst { it.type == ActionType.TEXT_INPUT }
        val confirm = indexOfStep("초기화 확정")
        assertTrue("입력칸 단계가 있어야 한다", field >= 0)
        assertTrue("입력칸을 먼저 눌러야 키보드가 뜬다", field < input)
        assertTrue("문구를 넣은 뒤에 확정해야 한다", input < confirm)
    }

    @Test
    fun `G 문구 입력이 실패하면 그냥 넘어가지 않는다`() {
        // 문구가 안 들어가면 초기화가 안 되고, 그 뒤 좌표 터치는 전부 엉뚱한 곳을 누른다.
        val input = steps.first { it.type == ActionType.TEXT_INPUT }
        assertEquals(OnTimeout.RESTART, input.onTimeout)
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
        // 실측: 실기기 화면(목표 아님) 0.414, 카드끼리 교차 0.671, 자기 매칭 1.000
        assertTrue("교차 매칭(0.671)보다 위여야 한다", preset.threshold > 0.671f)
        assertTrue("자기 매칭에 여유가 있어야 한다", preset.threshold < 0.95f)
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
    fun `H 이미지를 못 옮긴 카드는 타겟에서 빠진다`() {
        val applied = Macro().withPresetTargets(preset, now = 1L) { card ->
            if (card.assetFile.startsWith("target_01")) null else card.assetFile
        }
        // 이미지 없는 타겟은 언제나 조용히 실패하므로 아예 만들지 않는다.
        assertEquals(8, applied.targets.size)
        assertTrue(applied.targets.none { it.fileName.startsWith("target_01") })
    }

    // ------------------------------------------------------------------
    // I. 한 바퀴 전체
    // ------------------------------------------------------------------

    @Test
    fun `I 단계 순서가 흐름 정의와 같다`() {
        val expected = buildList<String> {
            addAll(RerollFlow.BEFORE_SCOUT.map { it.name })
            addAll(RerollFlow.SCOUT.map { it.name })
            addAll(RerollFlow.TO_COMBINE.map { it.name })
        }
        assertEquals(expected, steps.take(expected.size).map { it.name })

        val tail = buildList<String> {
            addAll(RerollFlow.AFTER_MISS.map { it.name })
            add(RerollFlow.RESET_INPUT_FIELD.name)
            add("초기화 확인 문구 입력")
            addAll(RerollFlow.AFTER_RESET_INPUT.map { it.name })
        }
        assertEquals(tail, steps.takeLast(tail.size).map { it.name })
    }

    @Test
    fun `I 단계 수가 좌표 수와 텍스트 입력 판정을 합한 값이다`() {
        val taps = RerollFlow.allTaps.size
        val checks = steps.count { it.type == ActionType.TARGET_CHECK }
        assertEquals(taps, steps.count { it.type == ActionType.TAP })
        assertEquals(1, steps.count { it.type == ActionType.TEXT_INPUT })
        assertEquals(taps + checks + 1, steps.size)
    }

    @Test
    fun `I 모든 단계가 켜져 있고 이름이 있다`() {
        assertTrue(steps.all { it.enabled })
        assertTrue(steps.all { it.name.isNotBlank() })
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
    }

    @Test
    fun `I 이미지 템플릿을 쓰지 않는다`() {
        // 흐름이 전부 좌표라 템플릿이 필요 없다. 남아 있으면 쓰이지 않는 이미지를
        // 복사만 하다가 언제 쓰이는지 헷갈린다.
        assertTrue(macro.templates.isEmpty())
        assertTrue(steps.all { it.templateIds.isEmpty() })
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
}
