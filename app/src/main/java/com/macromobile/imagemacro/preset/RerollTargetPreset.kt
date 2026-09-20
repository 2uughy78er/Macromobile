package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode

/**
 * 리세 목표카드 묶음 한 벌.
 *
 * 게임 이름이나 패키지명으로 갈라지는 코드를 엔진에 넣지 않는다. 이건 그냥 **사용자
 * 템플릿 묶음의 기본값**이고, 앱의 일반적인 타겟 저장 구조에 그대로 들어간다.
 * 가져온 뒤에는 사용자가 편집 화면에서 얼마든지 고칠 수 있다.
 */
data class TargetPreset(
    val id: String,
    val title: String,
    val description: String,
    /** assets 안의 폴더. 이 안의 PNG 가 타겟 이미지가 된다. */
    val assetDir: String,
    /** 아래 좌표/ROI 가 기준으로 삼는 해상도. */
    val referenceWidth: Int,
    val referenceHeight: Int,
    /** 목표 판정을 할 영역. 이 밖은 아예 보지 않는다. */
    val roi: Roi,
    val threshold: Float,
    val minMatchWidthRatio: Float,
    val maxMatchWidthRatio: Float,
    val requiredConsecutiveMatches: Int,
    val monitorIntervalMs: Long,
    /** 목표 판정 전에 연출이 끝나기를 기다리는 시간. */
    val settleDelayMs: Long,
    val cards: List<PresetCard>,
)

/** 묶음에 들어 있는 카드 한 장. */
data class PresetCard(
    /** assets 안의 파일 이름. */
    val assetFile: String,
    /** 화면과 기록에 보여줄 이름. */
    val displayName: String,
)

/**
 * 컴프야 리세 목표카드.
 *
 * ## 판정 위치
 *
 * 판정은 **조합 결과 화면의 상단 대형 카드** 한 자리에서만 한다. 스카우트 결과에서는
 * 하지 않는다. 그래서 타겟 감시(`monitorEnabled`)를 켜지 않는다 — 감시를 켜면 흐름
 * 어디서든(스카우트 결과 포함) 멈춰버려서 판정 위치를 한 곳으로 못박을 수 없다.
 * 판정은 [withRerollFlowSteps] 가 심어두는 TARGET_CHECK 단계에서만 일어난다.
 *
 * ## 이 숫자들이 어디서 나왔나
 *
 * 전부 실기기 캡처(2304×1440)를 **실제로 측정해서** 나온 값이다. 짐작한 값이 없다.
 *
 * - 확대 카드: 화면 좌표 x 994~1308, y 193~679 (314×486). 카드 이미지 비율과 맞는다.
 * - ROI (966,153,374,566) 은 그 카드를 여유 있게 감싸되, 작은 카드들이 시작되는
 *   y 760 에는 닿지 않는다(아래끝 719, 41px 여유). 그래서 작은 카드는 **구조적으로**
 *   검사 영역 밖이다.
 * - 크기 제한: 확대 카드 폭은 화면 너비의 314/2304 = 0.136. 배율 훑기(0.90~1.10)를
 *   감안해 0.105~0.175 로 잡았다.
 * - threshold: 실기기 화면(목표 아닌 카드)에 9장을 매칭한 최고 점수가 **0.414**,
 *   같은 자리에 목표카드를 넣으면 **1.000**, 카드끼리 교차 매칭 최대가 **0.671** 이었다.
 *   그 사이를 넉넉히 잡아 0.82 로 둔다. JPEG 압축·밝기·블러는 0.98 이상이라 여유가 있다.
 * - 배율은 반드시 훑어야 한다. 단일 배율로는 화면 카드가 ±3% 만 달라져도 자기 매칭이
 *   0.62~0.75 로 떨어져 교차 매칭(0.671)과 구분이 안 된다.
 */
object RerollTargetPreset {

    const val ID = "comprosepya_reroll"

    val COMPROSEPYA = TargetPreset(
        id = ID,
        title = "컴프야 리세 목표카드",
        description = "조합 결과 화면에서 상단 중앙에 크게 뜨는 카드만 검사합니다. " +
            "아래에 작게 깔리는 카드들은 검사 영역 밖이라 오검출되지 않습니다.",
        assetDir = "presets/comprosepya_reroll/targets",
        referenceWidth = RerollFlow.REFERENCE_WIDTH,
        referenceHeight = RerollFlow.REFERENCE_HEIGHT,
        roi = Roi(x = 966, y = 153, width = 374, height = 566),
        threshold = 0.82f,
        minMatchWidthRatio = 0.105f,
        maxMatchWidthRatio = 0.175f,
        requiredConsecutiveMatches = 2,
        monitorIntervalMs = 150L,
        settleDelayMs = RerollFlow.STEP_DELAY_MS,
        cards = listOf(
            PresetCard("target_01_joseonghwan.png", "조성환 76 2B 롯데"),
            PresetCard("target_02_munbogyeong.png", "문보경 76 1B LG"),
            PresetCard("target_03_kimyeongwoong.png", "김영웅 76 3B 삼성"),
            PresetCard("target_04_kimdongjub.png", "김동주B 76 3B 두산"),
            PresetCard("target_05_kimdoyoung.png", "김도영 76 3B KIA"),
            PresetCard("target_06_kimdoyoung_alt.png", "김도영 (다른 카드)"),
            PresetCard("target_07_mundongju.png", "문동주 74 RP 한화"),
            PresetCard("target_08_gujaguk_24.png", "구자욱'24 71 LF 삼성"),
            PresetCard("target_09_leejeonghu_22.png", "이정후'22 77 CF 키움"),
        ),
    )

    val ALL = listOf(COMPROSEPYA)

    fun byId(id: String): TargetPreset? = ALL.firstOrNull { it.id == id }
}

/**
 * 프리셋을 매크로에 얹는다.
 *
 * 매크로 자체(단계들)는 건드리지 않고 **타겟만** 갈아 끼운다. 사용자가 이미 만들어 둔
 * 단계가 있으면 그대로 살아 있어야 하기 때문이다.
 *
 * [fileNameOf] 는 각 카드 이미지가 실제로 저장된 파일 이름을 돌려준다. 저장에 실패한
 * 카드는 null 을 돌려주면 되고, 그런 카드는 타겟 목록에서 빠진다 — 이미지가 없는
 * 타겟을 만들어 두면 매칭이 조용히 실패하기 때문이다.
 */
fun Macro.withPresetTargets(
    preset: TargetPreset,
    now: Long,
    fileNameOf: (PresetCard) -> String?,
): Macro {
    val targets = preset.cards.mapNotNull { card ->
        val stored = fileNameOf(card) ?: return@mapNotNull null
        Target(
            name = card.displayName,
            fileName = stored,
            referenceScreenWidth = preset.referenceWidth,
            referenceScreenHeight = preset.referenceHeight,
            searchRegion = preset.roi,
            threshold = preset.threshold,
            minMatchWidthRatio = preset.minMatchWidthRatio,
            maxMatchWidthRatio = preset.maxMatchWidthRatio,
            enabled = true,
            createdAt = now,
        )
    }
    return copy(
        // 매크로에 기준 해상도가 아직 없으면 프리셋 기준을 쓴다. 이미 있으면 존중한다.
        referenceWidth = if (referenceWidth > 0) referenceWidth else preset.referenceWidth,
        referenceHeight = if (referenceHeight > 0) referenceHeight else preset.referenceHeight,
        targets = targets,
        targetSettings = targetSettings.copy(
            mode = TargetMatchMode.ANY,
            threshold = preset.threshold,
            roi = preset.roi,
            settleDelayMs = preset.settleDelayMs,
            // 감시는 끈다. 판정 위치를 "조합 결과 상단 대형 카드" 한 곳으로 묶어두기
            // 위해서다. 감시를 켜면 스카우트 결과 화면에서도 멈출 수 있다.
            monitorEnabled = false,
            monitorIntervalMs = preset.monitorIntervalMs,
            requiredConsecutiveMatches = preset.requiredConsecutiveMatches,
            saveScreenshotOnFound = true,
        ),
        updatedAt = now,
    )
}

/**
 * 리세 한 바퀴를 단계로 만든다.
 *
 * 순서는 [RerollFlow] 그대로다.
 *
 * ```
 * BEFORE_SCOUT → SCOUT → TO_COMBINE
 *        ↓
 * TARGET_CHECK  ← 조합 결과 상단 대형 카드. 목표면 여기서 멈춘다.
 *        ↓ 목표가 아닐 때만
 * AFTER_MISS → 초기화 문구 입력 → AFTER_RESET_INPUT → 다음 바퀴
 * ```
 *
 * 목표카드를 찾으면 [MacroStep] 실행이 TargetHit 으로 끝나고 엔진이 TARGET_FOUND 로
 * 간다. 그 아래 단계(확인 누르기·계정 초기화·다음 바퀴)는 **실행되지 않는다.** 애써 뽑은
 * 카드를 날리지 않으려면 이 순서가 반드시 지켜져야 한다.
 *
 * 카드 선택은 전부 **좌표**다. 조합 재료나 라인업에 어떤 선수가 나오는지는 매 리세마다
 * 달라서 이름이나 그림으로 고를 수 없다. 선수 이름으로 갈라지는 분기는 어디에도 없다.
 */
fun Macro.withRerollFlowSteps(preset: TargetPreset, now: Long): Macro {
    val steps = buildList<MacroStep> {
        addAll(RerollFlow.BEFORE_SCOUT.map(::tapStep))
        addAll(RerollFlow.SCOUT.map(::tapStep))
        addAll(RerollFlow.TO_COMBINE.map(::tapStep))

        // 조합 결과. 여기서만 목표를 판정한다.
        add(targetCheckStep(preset, "목표카드 검사 (조합 결과 상단 대형 카드)"))
        // 연출이 늦게 끝나 첫 검사가 빈 자리를 봤을 수도 있다. 한 번 더 본다.
        // 놓치면 바로 아래에서 계정이 초기화돼 카드가 영영 사라지기 때문에,
        // 표본을 하나 더 두는 값이 충분히 크다. 오검출 쪽 위험은 늘지 않는다 —
        // 각 검사가 threshold 와 연속 판정을 똑같이 거친다.
        add(targetCheckStep(preset, "목표카드 재검사 (연출이 늦을 때)"))

        addAll(RerollFlow.AFTER_MISS.map(::tapStep))
        add(tapStep(RerollFlow.RESET_INPUT_FIELD))
        add(
            MacroStep(
                type = ActionType.TEXT_INPUT,
                name = "초기화 확인 문구 입력",
                text = RerollFlow.RESET_CONFIRM_TEXT,
                clearBeforeInput = true,
                afterDelayMs = RerollFlow.STEP_DELAY_MS,
                // 문구가 안 들어가면 초기화가 안 되고, 그 뒤 좌표 터치는 전부 엉뚱한
                // 곳을 누른다. 그냥 넘기지 말고 한 바퀴를 처음부터 다시 돈다.
                onTimeout = OnTimeout.RESTART,
            ),
        )
        addAll(RerollFlow.AFTER_RESET_INPUT.map(::tapStep))
    }

    return copy(
        referenceWidth = preset.referenceWidth,
        referenceHeight = preset.referenceHeight,
        // 단계가 전부 좌표 터치라 템플릿 이미지를 쓰지 않는다.
        templates = emptyList(),
        steps = steps,
        repeat = repeat.copy(
            count = -1,              // 목표를 찾을 때까지 계속 돈다
            stopOnTargetFound = true,
            stopOnSuccess = false,
            cycleDelayMs = RerollFlow.STEP_DELAY_MS,
        ),
        updatedAt = now,
    )
}

private fun tapStep(tap: RerollTap): MacroStep = MacroStep(
    type = ActionType.TAP,
    name = tap.name,
    point = tap.point(),
    afterDelayMs = RerollFlow.STEP_DELAY_MS,
)

private fun targetCheckStep(preset: TargetPreset, name: String): MacroStep = MacroStep(
    type = ActionType.TARGET_CHECK,
    name = name,
    roi = preset.roi,
    threshold = preset.threshold,
    afterDelayMs = 0L,
    // 목표가 없는 게 정상이다. 그냥 다음 단계로 간다.
    onTargetMissing = OnTimeout.SKIP,
)
