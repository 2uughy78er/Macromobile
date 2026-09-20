package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.model.Template

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
    /** 단계 버튼 이미지가 들어 있는 assets 폴더. */
    val stepAssetDir: String,
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
    /** 목표 판정 전에 화면이 자리잡기를 기다리는 시간. */
    val settleDelayMs: Long,
    /** 버튼 이미지를 찾는 기본 최대 시간. */
    val findTimeoutMs: Long,
    /** 연출이 긴 화면에서 버튼 이미지를 찾는 최대 시간. */
    val slowFindTimeoutMs: Long,
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
 * 어디서든 멈춰버려서 판정 위치를 한 곳으로 못박을 수 없다. 판정은
 * [withRerollFlowSteps] 가 심어두는 TARGET_CHECK 단계에서만 일어난다.
 *
 * ## 이 숫자들이 어디서 나왔나
 *
 * 전부 실기기 캡처(2304x1440)를 **실제로 측정해서** 나온 값이다. 짐작한 값이 없다.
 *
 * - 조합 결과의 대형 카드 자리: x 997~1310, y 194~677 (313x483). 뽑기 결과 화면의
 *   카드(314x488)와 사실상 같은 크기라 같은 템플릿이 양쪽에서 쓰인다.
 * - ROI (966,153,374,566) 은 그 카드를 여유 있게 감싸되, 아래에 깔리는 작은 카드들
 *   (y 740 부터)에는 닿지 않는다. 그래서 작은 카드는 **구조적으로** 검사 영역 밖이다.
 *   사용자가 표시한 영역(990,168,346,536)도 이 안에 들어온다. 이쪽을 쓰는 이유는
 *   배율 훑기의 가장 큰 배율(1.10)까지 템플릿이 들어갈 자리가 필요해서다.
 * - 크기 제한: 대형 카드 폭은 화면 너비의 313/2304 = 0.136, 작은 카드는 약 0.068.
 *   배율 훑기(0.90~1.10)를 감안해 0.105~0.175 로 잡았다.
 *
 * ## 실제 조합 결과 화면으로 검증했다
 *
 * 목표가 아닌 카드가 떠 있는 실기기 캡처 두 장에 9장을 매칭한 결과:
 *
 * | 무엇 | 점수 |
 * |---|---|
 * | 목표 아닌 화면 최고 | **0.446 / 0.459** |
 * | 같은 자리에 목표카드를 넣었을 때 자기 매칭 | **0.904 ~ 0.948** |
 * | 카드끼리 교차 매칭 최고 | **0.666** |
 *
 * threshold 0.82 는 교차(0.666)보다 0.154 위, 가장 약한 자기 매칭(0.904)보다 0.084
 * 아래다. 배율은 반드시 훑어야 한다 — 단일 배율로는 화면 카드가 ±3%만 달라져도
 * 자기 매칭이 0.62~0.75 로 떨어져 교차 매칭과 구분이 안 된다.
 */
object RerollTargetPreset {

    const val ID = "comprosepya_reroll"

    val COMPROSEPYA = TargetPreset(
        id = ID,
        title = "컴프야 리세 목표카드",
        description = "조합 결과 화면에서 상단 중앙에 크게 뜨는 카드만 검사합니다. " +
            "아래에 작게 깔리는 카드들은 검사 영역 밖이라 오검출되지 않습니다.",
        assetDir = "presets/comprosepya_reroll/targets",
        stepAssetDir = RerollFlow.STEP_ASSET_DIR,
        referenceWidth = RerollFlow.REFERENCE_WIDTH,
        referenceHeight = RerollFlow.REFERENCE_HEIGHT,
        roi = Roi(x = 966, y = 153, width = 374, height = 566),
        threshold = 0.82f,
        minMatchWidthRatio = 0.105f,
        maxMatchWidthRatio = 0.175f,
        requiredConsecutiveMatches = 2,
        monitorIntervalMs = 150L,
        settleDelayMs = RerollFlow.STEP_DELAY_MS,
        findTimeoutMs = 20_000L,
        // 조합·스카우트 연출은 길다. 최대 대기일 뿐, 버튼이 뜨면 바로 누른다.
        slowFindTimeoutMs = 60_000L,
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

    /**
     * 연출이 끝날 때까지 오래 기다려야 하는 단계.
     *
     * 이 단계들은 **앞 단계를 누른 뒤 게임이 긴 연출을 돌린다.** 나머지 단계처럼 20초만
     * 기다리면 연출 도중에 포기해버린다.
     */
    val SLOW_STEPS = setOf(
        "스카우트 결과 - 확인",   // 연속 스카우트 9회 연출
        "선택하기",              // 조합 카드 뒤집기 연출
        "조합 결과 - 확인",       // 조합 결과 공개 연출
        "초기화 완료 - 확인",     // 계정 초기화 처리
        "팀 선택 - 선택",         // 초기화 뒤 게임이 다시 뜰 때까지
    )

    /**
     * 떠 있을 수도, 없을 수도 있는 안내 팝업.
     *
     * 없을 때 20초를 기다렸다가 한 바퀴를 버리면 안 된다. 짧게 보고 없으면 넘어간다.
     */
    val OPTIONAL_STEPS = setOf(
        "안내 닫기 (X)",
        "조합 안내 닫기 (X)",
    )
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
 * 순서는 [RerollFlow.STEPS] 그대로다. 각 단계가 어떤 동작이 되는지:
 *
 * | 흐름 | 동작 | 왜 |
 * |---|---|---|
 * | [ImageTap] | WAIT_AND_TAP | 버튼 그림이 뜰 때까지 기다렸다 누른다 |
 * | [ImageTap] (안내 팝업) | TAP_IF_FOUND | 없을 수도 있으니 짧게 보고 넘어간다 |
 * | [Tap] | TAP | 매번 그림이 달라지는 자리라 좌표로 누른다 |
 * | [TargetCheck] | WAIT_FOR_IMAGE + TARGET_CHECK | 결과 화면이 뜬 걸 확인하고 판정 |
 * | [TextInput] | TEXT_INPUT | 초기화 확인 문구 |
 *
 * 목표카드를 찾으면 TARGET_CHECK 가 TargetHit 으로 끝나고 엔진이 TARGET_FOUND 로
 * 간다. 그 아래 단계(확인 누르기·계정 초기화·다음 바퀴)는 **실행되지 않는다.** 애써
 * 뽑은 카드를 날리지 않으려면 이 순서가 반드시 지켜져야 한다.
 *
 * [templateFileOf] 는 버튼 이미지가 실제로 저장된 파일 이름을 돌려준다. 저장에 실패해
 * null 이 오면 그 단계는 만들지 않는다 — 이미지 없는 단계는 언제나 시간만 끌다가
 * 실패하는데, 화면에는 정상처럼 보여서 원인을 찾기 어렵다.
 */
fun Macro.withRerollFlowSteps(
    preset: TargetPreset,
    now: Long,
    templateFileOf: (ImageTap) -> String?,
): Macro {
    // 같은 그림을 여러 단계가 쓰더라도(예: 대화상자 [확인]) 단계마다 템플릿을 따로
    // 만든다. 찾는 영역이 단계마다 달라야 엉뚱한 화면의 같은 버튼이 잡히지 않는다.
    val templates = LinkedHashMap<ImageTap, Template>()
    RerollFlow.imageTaps.forEach { step ->
        val stored = templateFileOf(step) ?: return@forEach
        templates[step] = Template(
            name = step.name,
            fileName = stored,
            referenceScreenWidth = preset.referenceWidth,
            referenceScreenHeight = preset.referenceHeight,
            searchRegion = step.searchRegion(),
            threshold = BUTTON_THRESHOLD,
            createdAt = now,
        )
    }

    val flow = RerollFlow.STEPS
    val steps = ArrayList<MacroStep>(flow.size + 1)
    flow.forEachIndexed { index, step ->
        when (step) {
            is ImageTap -> templates[step]?.let { steps += imageStep(preset, step, it) }
            is Tap -> steps += MacroStep(
                type = ActionType.TAP,
                name = step.name,
                point = step.point(),
                afterDelayMs = RerollFlow.STEP_DELAY_MS,
            )
            is TextInput -> steps += MacroStep(
                type = ActionType.TEXT_INPUT,
                name = step.name,
                text = step.text,
                clearBeforeInput = true,
                afterDelayMs = RerollFlow.STEP_DELAY_MS,
                // 문구가 안 들어가면 초기화가 안 되고, 그 뒤 단계는 전부 헛돈다.
                onTimeout = OnTimeout.RESTART,
            )
            TargetCheck -> {
                // 조합 결과 화면이 실제로 떴는지부터 확인한다. 연출 길이를 짐작해서
                // 몇 초 기다리는 대신, 결과 화면의 [확인] 버튼이 보일 때까지 기다린다.
                nextImageTap(flow, index)?.let { after ->
                    templates[after]?.let { tpl ->
                        steps += MacroStep(
                            type = ActionType.WAIT_FOR_IMAGE,
                            name = "조합 결과 화면 기다리기",
                            templateIds = listOf(tpl.id),
                            timeoutMs = preset.slowFindTimeoutMs,
                            pollIntervalMs = POLL_MS,
                            afterDelayMs = 0L,
                            onTimeout = OnTimeout.RESTART,
                        )
                    }
                }
                steps += MacroStep(
                    type = ActionType.TARGET_CHECK,
                    name = step.name,
                    roi = preset.roi,
                    threshold = preset.threshold,
                    afterDelayMs = 0L,
                    // 목표가 없는 게 정상이다. 그냥 다음 단계로 간다.
                    onTargetMissing = OnTimeout.SKIP,
                )
            }
        }
    }

    return copy(
        referenceWidth = preset.referenceWidth,
        referenceHeight = preset.referenceHeight,
        templates = templates.values.toList(),
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

/** [from] 다음에 오는 첫 [ImageTap]. 없으면 null. */
private fun nextImageTap(flow: List<RerollStep>, from: Int): ImageTap? =
    flow.drop(from + 1).filterIsInstance<ImageTap>().firstOrNull()

private fun imageStep(preset: TargetPreset, step: ImageTap, template: Template): MacroStep {
    val optional = step.name in RerollTargetPreset.OPTIONAL_STEPS
    val timeout = when {
        optional -> OPTIONAL_TIMEOUT_MS
        step.name in RerollTargetPreset.SLOW_STEPS -> preset.slowFindTimeoutMs
        else -> preset.findTimeoutMs
    }
    return MacroStep(
        type = if (optional) ActionType.TAP_IF_FOUND else ActionType.WAIT_AND_TAP,
        name = step.name,
        templateIds = listOf(template.id),
        roi = step.searchRegion(),
        timeoutMs = timeout,
        pollIntervalMs = POLL_MS,
        afterDelayMs = RerollFlow.STEP_DELAY_MS,
        onTimeout = OnTimeout.RESTART,
    )
}

/**
 * 버튼 이미지의 기준 유사도.
 *
 * 카드(0.82)보다 높게 잡는다. 버튼은 화면마다 모양이 거의 같아서 조금만 느슨해도
 * 옆 화면의 같은 버튼이 잡힌다. 찾는 영역까지 좁혀 뒀으니 이 정도면 충분하다.
 */
private const val BUTTON_THRESHOLD = 0.88f
private const val POLL_MS = 200L
private const val OPTIONAL_TIMEOUT_MS = 4_000L
