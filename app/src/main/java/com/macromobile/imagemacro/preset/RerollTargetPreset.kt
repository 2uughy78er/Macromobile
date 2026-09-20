package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.TargetMatchMode
import com.macromobile.imagemacro.model.TargetSettings

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
 * **실기기 캡처로 다시 재야 하는 값이다.** 준 이미지는 창 모드 캡처로 보이므로,
 * 실제 MediaProjection 캡처에서 ROI 와 threshold 를 확인하고 필요하면 고쳐야 한다.
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
