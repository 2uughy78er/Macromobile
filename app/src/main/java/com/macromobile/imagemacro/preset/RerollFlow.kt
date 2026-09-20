package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.RefPoint
import com.macromobile.imagemacro.model.Roi

/**
 * 리세 한 바퀴의 한 단계.
 *
 * ## 이미지로 누를 것과 좌표로 누를 것
 *
 * 화면마다 사용자가 표시해 준 것을 그대로 옮겼다.
 *
 * - **네모로 표시된 버튼** → [ImageTap]. 그 버튼 그림을 찾아서 누른다. 연출이 길어져도
 *   화면이 뜰 때까지 기다렸다가 누르므로, 고정 시간에 기대지 않는다.
 * - **빨간 점으로 표시된 자리** → [Tap]. 카드 목록처럼 **매 리세마다 다른 선수가 뜨는
 *   자리**라 그림으로는 찾을 수 없다. 그래서 좌표로 누른다.
 *
 * 그러니 [Tap] 의 이름은 특정 선수를 뜻하지 않는다. **자리**를 뜻한다.
 * 코드 어디에도 선수 이름으로 갈라지는 분기를 넣지 않는다.
 */
sealed interface RerollStep {
    val name: String
}

/** 버튼 그림을 찾아서 누른다. [box] 는 그 버튼이 화면에서 차지하는 자리다. */
data class ImageTap(
    override val name: String,
    /** assets 안의 파일 이름. */
    val assetFile: String,
    /** 기준 해상도에서 버튼이 놓인 자리. 템플릿을 잘라낸 영역이기도 하다. */
    val box: Roi,
    /**
     * 이 버튼만 찾을 영역을 [box] 에서 얼마나 넓힐지.
     *
     * 화면 전체를 뒤지면 안 된다. 같은 모양의 [확인] 버튼이 여러 화면에 있어서
     * 엉뚱한 것이 잡힌다. 자기 자리 근처만 본다.
     */
    val searchMargin: Int = DEFAULT_SEARCH_MARGIN,
) : RerollStep {
    /** 이 버튼을 찾을 영역. 화면 밖으로 나가지 않게 잘라낸다. */
    fun searchRegion(): Roi {
        val left = (box.x - searchMargin).coerceAtLeast(0)
        val top = (box.y - searchMargin).coerceAtLeast(0)
        val right = (box.right + searchMargin).coerceAtMost(RerollFlow.REFERENCE_WIDTH)
        val bottom = (box.bottom + searchMargin).coerceAtMost(RerollFlow.REFERENCE_HEIGHT)
        return Roi(left, top, right - left, bottom - top)
    }

    companion object {
        const val DEFAULT_SEARCH_MARGIN = 80
    }
}

/** 좌표를 그대로 누른다. 매번 그림이 달라지는 자리에만 쓴다. */
data class Tap(
    override val name: String,
    /** 기준 해상도(2304x1440) 좌표. */
    val x: Int,
    val y: Int,
) : RerollStep {
    fun point(): RefPoint = RefPoint(x, y)
}

/** 조합 결과의 상단 대형 카드가 목표카드인지 본다. 목표면 여기서 멈춘다. */
data object TargetCheck : RerollStep {
    override val name: String = "목표카드 판정 (조합 결과 상단 대형 카드)"
}

/** 초기화 확인 문구를 넣는다. */
data class TextInput(
    override val name: String,
    val text: String,
) : RerollStep

/**
 * 컴프야 리세 한 바퀴.
 *
 * 좌표와 버튼 자리는 전부 **2304x1440 실기기 캡처 50장에서 실제로 재서** 나왔다.
 * 다른 해상도에서는 기존 CoordinateMapper 가 비율로 옮긴다.
 *
 * 흐름:
 * ```
 * 팀 선택 → 주력 선수 → 튜토리얼 스킵 → 라인업 정리 → 우편 수령
 *        → 라이브 스카우트 연속 구매 → 조합 재료 3장 → 보호 아이템 → 조합하기
 *        → [목표카드 판정]  ← 목표면 여기서 멈춘다
 *        → (목표가 아닐 때만) 결과 정리 → 계정 초기화 → 문구 입력 → 다음 바퀴
 * ```
 */
object RerollFlow {

    /** 좌표가 기준으로 삼는 해상도. */
    const val REFERENCE_WIDTH = 2304
    const val REFERENCE_HEIGHT = 1440

    /**
     * 한 단계를 끝낸 뒤 쉬는 시간.
     *
     * 최대 대기 시간이 아니라 **화면이 넘어갈 틈**이다. 버튼을 찾는 쪽은 이미지가
     * 뜰 때까지 따로 기다린다([ImageTap] 단계의 timeout).
     */
    const val STEP_DELAY_MS = 2_000L

    /**
     * 초기화를 확정하려면 이 문구를 그대로 입력해야 한다.
     *
     * 대소문자와 띄어쓰기를 바꾸면 게임이 받아주지 않는다.
     */
    const val RESET_CONFIRM_TEXT = "COM2US PROBASEBALL"

    /** 단계 이미지가 들어 있는 assets 폴더. */
    const val STEP_ASSET_DIR = "presets/comprosepya_reroll/steps"

    private fun img(n: Int, name: String, x: Int, y: Int, w: Int, h: Int) =
        ImageTap(name, "step_%02d.png".format(n), Roi(x, y, w, h))

    private fun tap(name: String, x: Int, y: Int) = Tap(name, x, y)

    /**
     * 한 바퀴 전체. 위에서 아래로 그대로 실행한다.
     *
     * [TargetCheck] 아래의 단계들은 **목표카드가 아닐 때만** 실행된다. 목표를 찾으면
     * 매크로가 거기서 멈추므로 확인을 누르거나 계정을 초기화하는 일이 없다.
     */
    val STEPS: List<RerollStep> = listOf(
        img(1, "팀 선택 - 선택", 1160, 1236, 256, 60),
        img(2, "주력 타자 자리", 1316, 760, 180, 92),
        img(3, "선수 정보 - 선택", 1166, 1314, 280, 68),
        img(4, "주력 투수 자리", 1330, 752, 172, 96),
        img(5, "선수 정보 - 선택", 1166, 1314, 280, 68),
        img(6, "구단 선택 - 확인", 1166, 1002, 246, 68),
        img(7, "튜토리얼 스킵", 1314, 756, 160, 60),
        img(8, "대화상자 확인", 1166, 868, 232, 72),
        img(9, "라인업 탭", 800, 1330, 200, 108),
        img(10, "안내 닫기 (X)", 1708, 36, 72, 72),
        img(11, "라인업 카드 자리", 1176, 430, 128, 172),
        img(12, "교체", 1460, 1268, 240, 66),
        img(13, "보관 선수 카드 자리 1", 504, 792, 146, 196),
        img(14, "교체 확정", 1000, 1272, 300, 64),
        img(15, "우편함", 1714, 18, 72, 68),
        img(16, "라인업 저장 - 확인", 1166, 868, 232, 72),
        img(17, "모두받기", 1376, 1214, 352, 80),
        img(18, "보상 획득 - 확인", 988, 976, 330, 70),
        img(19, "보관함 탭", 1374, 164, 196, 54),
        img(20, "라이브 스카우트 티켓", 1564, 364, 124, 204),
        img(21, "이동하기", 1052, 990, 200, 66),
        img(22, "라이브 연속 구매하기", 1266, 1172, 214, 86),
        img(23, "연속 구매하기", 1056, 1216, 204, 68),
        img(24, "연속 스카우트 - 확인", 1166, 868, 232, 72),
        img(25, "스카우트 결과 - 확인", 1172, 1294, 308, 88),
        img(26, "스카우트 뒤로 가기", 1060, 1390, 180, 36),
        img(27, "구단 관리 탭", 1596, 1348, 164, 86),
        img(28, "조합", 1480, 1058, 172, 128),
        img(29, "조합 안내 닫기 (X)", 1708, 36, 72, 72),
        img(30, "선호 구단 제외 토글", 672, 756, 106, 48),
        img(31, "정렬 방향", 1738, 754, 54, 50),
        tap("조합 재료 자리 1", 584, 911),
        tap("조합 재료 자리 2", 718, 905),
        tap("조합 재료 자리 3", 865, 903),
        img(35, "보호 아이템 슬롯", 1358, 604, 110, 112),
        img(36, "임팩트 등급 보호권", 1058, 376, 180, 200),
        img(37, "보호 아이템 - 확인", 976, 1060, 356, 70),
        img(38, "조합하기", 1158, 1306, 626, 70),
        tap("조합 결과 카드 자리 1", 914, 662),
        tap("조합 결과 카드 자리 2", 1153, 665),
        img(41, "선택하기", 978, 1318, 354, 74),
        TargetCheck,
        img(43, "조합 결과 - 확인", 1074, 1310, 344, 74),
        img(44, "조합 뒤로 가기", 1056, 1394, 180, 36),
        img(45, "게임설정", 1506, 1204, 212, 112),
        img(46, "계정 탭", 1372, 202, 116, 72),
        img(47, "게임 초기화", 936, 1042, 208, 40),
        img(48, "초기화 확인", 1160, 912, 254, 62),
        img(49, "초기화 재확인", 1160, 912, 254, 62),
        img(50, "초기화 문구 입력칸", 874, 652, 556, 58),
        TextInput("초기화 확인 문구 입력", RESET_CONFIRM_TEXT),
        img(51, "초기화 확정", 1160, 912, 254, 62),
        img(52, "초기화 완료 - 확인", 984, 868, 336, 70),
    )

    /** 좌표로 누르는 자리들. 그림이 매번 달라져서 이미지로는 찾을 수 없는 곳이다. */
    val coordinateTaps: List<Tap> get() = STEPS.filterIsInstance<Tap>()

    /** 그림으로 찾아 누르는 버튼들. */
    val imageTaps: List<ImageTap> get() = STEPS.filterIsInstance<ImageTap>()
}
