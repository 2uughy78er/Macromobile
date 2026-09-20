package com.macromobile.imagemacro.preset

import com.macromobile.imagemacro.model.RefPoint

/**
 * 리세 흐름의 터치 한 번.
 *
 * ## 왜 좌표인가
 *
 * 조합 재료나 라인업 카드는 **리세할 때마다 다른 선수가 나온다.** 이름이나 카드 그림으로
 * 고르면 매번 달라져서 맞출 수가 없다. 그래서 "목록에서 몇 번째 자리"를 누른다.
 *
 * 그러니 아래 좌표들은 특정 선수를 뜻하지 않는다. **자리**를 뜻한다.
 * 코드 어디에도 선수 이름으로 갈라지는 분기를 넣지 않는다.
 */
data class RerollTap(
    /** 사람이 읽을 단계 이름. 무엇을 누르는 자리인지만 적는다. */
    val name: String,
    /** 기준 해상도(2304×1440) 좌표. */
    val x: Int,
    val y: Int,
) {
    fun point(): RefPoint = RefPoint(x, y)
}

/**
 * 컴프야 리세 한 바퀴.
 *
 * 좌표는 전부 **2304×1440 기준**이고, 다른 해상도에서는 기존 CoordinateMapper 가
 * 비율로 옮긴다. 화면마다 터치 뒤 [STEP_DELAY_MS] 만큼 쉬어서 화면 전환을 기다린다.
 *
 * 흐름은 네 덩어리다.
 * ```
 * BEFORE_SCOUT  팀 선택 → 튜토리얼 → 라인업 정리 → 우편 수령 → 티켓으로 이동
 * SCOUT         라이브 연속 구매 → 결과 닫기
 * TO_COMBINE    구단 관리 → 조합 → 재료 3장 → 보호 아이템 → 조합하기
 * ── 여기서 조합 결과의 상단 대형 카드를 검사한다 ──
 * AFTER_MISS    (목표가 아닐 때만) 결과 정리 → 계정 초기화 → 문구 입력 → 처음으로
 * ```
 */
object RerollFlow {

    /** 좌표가 기준으로 삼는 해상도. */
    const val REFERENCE_WIDTH = 2304
    const val REFERENCE_HEIGHT = 1440

    /**
     * 터치 한 번 뒤에 쉬는 시간.
     *
     * 최대 대기 시간이 아니라 **기본 안정 대기**다. 연속으로 빠르게 누르면 화면이 아직
     * 안 바뀐 상태에서 다음 터치가 들어가 엉뚱한 곳이 눌린다.
     */
    const val STEP_DELAY_MS = 2_000L

    /** 팀 선택부터 스카우트 티켓으로 이동하기까지. */
    val BEFORE_SCOUT = listOf(
        RerollTap("팀 선택", 1305, 1260),
        RerollTap("주력 타자 자리", 1825, 540),
        RerollTap("선수 정보 확인", 1325, 1353),
        RerollTap("주력 투수 자리", 1415, 1055),
        RerollTap("선수 정보 확인", 1325, 1353),
        RerollTap("구단 선택 확인", 1290, 1370),
        RerollTap("튜토리얼 스킵", 1390, 785),
        RerollTap("스킵 확인", 1460, 1200),
        RerollTap("라인업", 1205, 1390),
        RerollTap("안내 닫기", 1725, 70),
        RerollTap("라인업 카드 자리", 1240, 510),
        RerollTap("교체", 1620, 1365),
        RerollTap("교체할 카드 자리", 575, 885),
        RerollTap("교체 확정", 1110, 1310),
        RerollTap("우편함", 1750, 65),
        RerollTap("라인업 변경 확인", 1260, 1210),
        RerollTap("모두 받기", 1550, 1250),
        RerollTap("보상 확인", 1235, 1010),
        RerollTap("보관함", 1445, 190),
        RerollTap("라이브 스카우트 티켓", 1505, 460),
        RerollTap("이동하기", 1185, 1020),
    )

    /** 라이브 스카우트 연속 구매. */
    val SCOUT = listOf(
        RerollTap("라이브 연속 구매하기", 1365, 1215),
        RerollTap("연속 구매하기", 1155, 1250),
        RerollTap("연속 스카우트 확인", 1260, 1210),
        RerollTap("스카우트 결과 확인", 1320, 1325),
        RerollTap("뒤로가기", 1130, 1415),
    )

    /**
     * 조합 화면으로 가서 재료를 고르고 조합을 실행한다.
     *
     * 재료 세 장은 **목록의 자리**를 누르는 것이다. 어떤 선수가 거기 있든 상관없다.
     */
    val TO_COMBINE = listOf(
        RerollTap("구단 관리", 1670, 1390),
        RerollTap("조합", 1565, 1125),
        RerollTap("안내 닫기", 1725, 65),
        RerollTap("선호 구단 제외 토글", 720, 780),
        RerollTap("정렬/필터", 1765, 780),
        RerollTap("조합 재료 자리 1", 584, 910),
        RerollTap("조합 재료 자리 2", 719, 905),
        RerollTap("조합 재료 자리 3", 864, 905),
        RerollTap("보호 아이템", 1150, 470),
        RerollTap("보호 아이템 확인", 1125, 1088),
        RerollTap("조합하기", 1427, 1341),
    )

    /**
     * 목표카드가 **아닐 때만** 실행한다.
     *
     * 목표카드를 찾으면 매크로가 여기 닿기 전에 멈춘다. 그래야 확인 버튼을 누르거나
     * 계정을 초기화해서 애써 뽑은 카드를 날리는 일이 없다.
     */
    val AFTER_MISS = listOf(
        RerollTap("결과 카드 자리 1", 912, 666),
        RerollTap("결과 카드 자리 2", 1153, 661),
        RerollTap("선택하기", 1150, 1350),
        RerollTap("결과 확인", 1260, 1344),
        RerollTap("조합 화면 뒤로가기", 1066, 1410),
        RerollTap("구단 관리", 1601, 1258),
        RerollTap("게임 설정", 1601, 1258),
        RerollTap("계정 탭", 1427, 234),
        RerollTap("게임 초기화", 1033, 1066),
        RerollTap("초기화 확인", 1246, 945),
        RerollTap("초기화 재확인", 1242, 949),
    )

    /** 초기화 확인 문구를 넣는 입력칸. */
    val RESET_INPUT_FIELD = RerollTap("초기화 문구 입력칸", 1137, 679)

    /**
     * 초기화를 확정하려면 이 문구를 그대로 입력해야 한다.
     *
     * 대소문자와 띄어쓰기를 바꾸면 게임이 받아주지 않는다.
     */
    const val RESET_CONFIRM_TEXT = "COM2US PROBASEBALL"

    /** 문구를 넣은 뒤. */
    val AFTER_RESET_INPUT = listOf(
        RerollTap("초기화 확정", 1272, 937),
        RerollTap("초기화 완료 확인", 1118, 901),
    )

    /** 한 바퀴에서 목표카드를 찾지 못했을 때 거치는 터치 수. */
    val allTaps: List<RerollTap>
        get() = BEFORE_SCOUT + SCOUT + TO_COMBINE + AFTER_MISS +
            listOf(RESET_INPUT_FIELD) + AFTER_RESET_INPUT
}
