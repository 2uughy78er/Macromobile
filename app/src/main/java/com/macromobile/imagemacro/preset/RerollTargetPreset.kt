package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.OnTimeout
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.model.TargetSettings
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
    val cards: List<PresetCard>,
    /** 목표카드가 아닐 때 눌러 다음 리세로 넘어가는 버튼. */
    val confirmButton: PresetButton,
    /** 단계 이미지가 들어 있는 assets 폴더. */
    val stepAssetDir: String,
)

/**
 * 결과 화면에서 눌러야 하는 버튼.
 *
 * 좌표가 아니라 **이미지**로 찾는다. 시간이나 고정 좌표로 누르면 연출 길이가 바뀌거나
 * 해상도가 다를 때 엉뚱한 곳을 누르게 된다.
 */
data class PresetButton(
    val assetFile: String,
    val displayName: String,
    /** 버튼을 찾을 영역(기준 해상도). 화면 전체를 뒤지지 않게 좁혀둔다. */
    val searchRegion: Roi,
    val threshold: Float,
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
 * ## 이 숫자들이 어디서 나왔나
 *
 * 전부 사용자가 준 결과 화면 한 장과 카드 9장을 **실제로 측정해서** 나온 값이다.
 * 짐작한 값이 하나도 없다.
 *
 * - 화면 1536×960 에서 확대 카드는 x 664~872, y 128~452 (209×325, 비율 0.643).
 *   카드 이미지 비율(984/1536 = 0.641)과 일치한다.
 * - 작은 카드들은 y 503 부터 시작한다. 그래서 ROI 아래끝을 479 로 두어 **작은 카드가
 *   구조적으로 검사 영역 밖**이 되게 했다.
 * - 크기 제한: 확대 카드는 화면 너비의 209/1536 = 0.136, 작은 카드는 약 0.072.
 *   배율 훑기 범위(0.90~1.10)를 감안해 0.105~0.175 로 잡았다.
 * - threshold: 카드 9장끼리 교차 매칭했을 때 최대 유사도가 0.686 이었고, 배율을
 *   훑으면 자기 매칭은 1.000 이 나왔다. 그 사이를 넉넉히 잡아 0.82 로 둔다.
 *   JPEG 압축·밝기·블러는 0.98 이상이라 여유가 충분하다.
 *
 * ## 실기기 캡처로 검증했다 (2304×1440)
 *
 * 처음에는 참고 이미지 한 장으로 추정한 값이었는데, 같은 화면을 실기기에서 캡처해
 * 다시 재보니 **레이아웃이 정확히 1.5배**였고(2304/1536 = 1440/960 = 1.5) 추정값이
 * 그대로 맞았다. 실측 결과:
 *
 * - 확대 카드: 화면 좌표 x 994~1308, y 193~679 (약 314×486). 기준 해상도 환산 시
 *   x 663~872, y 129~453 — 참고 이미지에서 잰 값과 1px 안쪽으로 일치한다.
 * - 목표가 아닌 카드(이재원B)가 떠 있는 실제 화면에 9장을 매칭한 최고 점수 **0.430**.
 *   threshold 0.82 와 큰 차이가 있어 오검출 여지가 없다.
 * - 같은 화면의 카드 자리에 목표카드를 넣고 매칭하면 **0.981~0.990**, 이때 다른 카드와의
 *   최대 유사도는 0.678 이었다.
 * - 매칭된 폭은 화면 너비의 **0.1359** 로 크기 제한(0.105~0.175) 한가운데였다.
 * - 작은 카드는 기기 좌표 y 760 부터 시작하고, ROI 아래끝은 719 라 41px 여유로 벗어난다.
 *
 * 그래서 이 값들은 추정이 아니라 실기기에서 확인된 값이다.
 */
object RerollTargetPreset {

    const val ID = "comprosepya_reroll"

    val COMPROSEPYA = TargetPreset(
        id = ID,
        title = "컴프야 리세 목표카드",
        description = "뽑기 결과에서 상단 중앙에 크게 확대되는 카드만 검사합니다. " +
            "아래에 작게 깔리는 카드들은 검사 영역 밖이라 오검출되지 않습니다.",
        assetDir = "presets/comprosepya_reroll/targets",
        referenceWidth = 1536,
        referenceHeight = 960,
        roi = Roi(x = 644, y = 102, width = 249, height = 377),
        threshold = 0.82f,
        minMatchWidthRatio = 0.105f,
        maxMatchWidthRatio = 0.175f,
        requiredConsecutiveMatches = 2,
        monitorIntervalMs = 150L,
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
        // 실기기 캡처에서 [확인] 버튼은 (1066,1308) 크기 359x73 이었다.
        // 기준 해상도(1536x960)로 환산하면 (711,872) 크기 239x49 다.
        confirmButton = PresetButton(
            assetFile = "confirm_button.png",
            displayName = "확인",
            // 버튼은 화면 아래쪽에만 있다. 위쪽 카드 영역을 뒤지지 않게 좁힌다.
            searchRegion = Roi(x = 560, y = 820, width = 540, height = 140),
            threshold = 0.88f,
        ),
        stepAssetDir = "presets/comprosepya_reroll/steps",
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
            monitorEnabled = true,
            monitorIntervalMs = preset.monitorIntervalMs,
            requiredConsecutiveMatches = preset.requiredConsecutiveMatches,
            saveScreenshotOnFound = true,
        ),
        updatedAt = now,
    )
}

/**
 * 결과 화면 단계를 얹는다.
 *
 * 지시서 §5 가 금지한 "몇 초 기다렸다가 좌표 클릭" 을 쓰지 않는다. 화면에 무엇이 보이는지로
 * 판단한다. §16 이 요구한 순서 — **목표 검사가 [확인] 터치보다 먼저** — 를 단계 순서로
 * 못박는다.
 *
 * ```
 * WAIT_FOR_IMAGE(확인 버튼)   결과 화면에 도달했는지 확인
 *         ↓
 * TARGET_CHECK               목표카드면 여기서 멈춘다 (TargetHit → RunState.TARGET_FOUND)
 *         ↓ 목표가 아닐 때만
 * WAIT_AND_TAP(확인 버튼)    다음 리세로
 * ```
 *
 * 타겟 감시(`monitorEnabled`)도 함께 켜지므로, 단계 사이에서 카드가 떠도 잡힌다.
 */
fun Macro.withPresetResultSteps(
    preset: TargetPreset,
    now: Long,
    confirmFileName: String?,
): Macro {
    val button = preset.confirmButton
    // 이미지를 못 옮겼으면 단계를 만들지 않는다. 이미지 없는 단계는 언제나 시간만
    // 끌다가 실패하는데, 화면에는 정상처럼 보여서 원인을 찾기 어렵다.
    val stored = confirmFileName ?: return this

    val template = Template(
        name = button.displayName,
        fileName = stored,
        referenceScreenWidth = preset.referenceWidth,
        referenceScreenHeight = preset.referenceHeight,
        searchRegion = button.searchRegion,
        threshold = button.threshold,
        createdAt = now,
    )

    val steps = listOf(
        MacroStep(
            type = ActionType.WAIT_FOR_IMAGE,
            name = "결과 화면 기다리기",
            templateIds = listOf(template.id),
            // 뽑기 연출이 길 수 있다. 버튼이 뜰 때까지는 넉넉히 기다린다.
            timeoutMs = 40_000L,
            pollIntervalMs = 200L,
            afterDelayMs = 0L,
            onTimeout = OnTimeout.RESTART,
        ),
        MacroStep(
            type = ActionType.TARGET_CHECK,
            name = "목표카드 검사 (확대 카드만)",
            roi = preset.roi,
            threshold = preset.threshold,
            // 연출 중간 프레임을 거르되 빨리 멈춰야 한다. 카드가 자리를 잡는 데
            // 걸리는 시간만 본다.
            timeoutMs = 4_000L,
            pollIntervalMs = preset.monitorIntervalMs,
            afterDelayMs = 0L,
            // 목표가 없으면 그냥 다음 단계로. 이게 정상 흐름이다.
            onTimeout = OnTimeout.SKIP,
        ),
        MacroStep(
            type = ActionType.WAIT_AND_TAP,
            name = "확인 눌러 다음 리세로",
            templateIds = listOf(template.id),
            timeoutMs = 15_000L,
            pollIntervalMs = 200L,
            afterDelayMs = 800L,
            onTimeout = OnTimeout.RESTART,
        ),
    )

    return copy(
        templates = templates.filterNot { it.name == button.displayName } + template,
        steps = steps,
        repeat = repeat.copy(
            count = -1,              // 목표를 찾을 때까지 계속 돈다
            stopOnTargetFound = true,
            stopOnSuccess = false,
        ),
        updatedAt = now,
    )
}
