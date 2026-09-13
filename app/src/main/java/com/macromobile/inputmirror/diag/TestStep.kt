package com.macromobile.inputmirror.diag

/**
 * 테스트 화면을 단계로 쪼갠 것.
 *
 * 이전 판은 좌표 변환·제스처 전송·오버레이 뷰·로그를 테스트 화면 하나에서 **동시에**
 * 초기화했다. 그래서 어느 하나가 죽으면 화면 전체가 죽고, 무엇이 죽였는지는 알 수 없었다.
 *
 * 이제 단계마다 무엇까지 켤지 고를 수 있다. 각 단계는 아래 단계를 모두 포함한다.
 * 크래시가 나면 마지막으로 통과한 단계가 곧 범인의 위치다.
 */
enum class TestStep(
    val number: Int,
    val title: String,
    val summary: String,
) {
    EMPTY(1, "빈 테스트 화면", "Compose 화면만 띄운다. 아무것도 초기화하지 않는다."),
    ACCESSIBILITY(2, "접근성 서비스 상태", "서비스 연결 여부와 제스처 한계치를 읽는다."),
    DISPLAY_METRICS(3, "DisplayMetrics", "화면 크기·밀도를 읽는다."),
    WINDOW_METRICS(4, "WindowMetrics / 인셋", "창 경계와 상태바·내비바 인셋을 읽는다."),
    VIEW(5, "테스트 뷰 생성", "빈 커스텀 뷰를 만들어 붙인다. 그리지는 않는다."),
    AREAS(6, "4분할 영역 그리기", "화면을 넷으로 나눠 테두리와 이름을 그린다."),
    GEOMETRY(7, "화면 좌표 변환", "뷰의 화면상 원점을 읽어 View↔Screen 변환을 만든다."),
    TOUCH(8, "MASTER 터치 수집", "MASTER 영역의 터치를 받아 화면에 그린다. 주입은 하지 않는다."),
    ONE_TARGET(9, "제스처 전송기 + TARGET 1개", "TARGET 1 에만 실제로 입력을 주입한다."),
    ALL_TARGETS(10, "TARGET 3개 전체", "TARGET 1~3 에 모두 주입한다. 최종 형태."),
    ;

    val label: String get() = "STEP $number · $title"

    /** 이 단계가 [other] 단계의 기능까지 포함하는가. */
    fun includes(other: TestStep): Boolean = number >= other.number

    /** 이 단계에서 사용할 TARGET 개수. */
    val targetCount: Int
        get() = when {
            number >= ALL_TARGETS.number -> 3
            number >= ONE_TARGET.number -> 1
            else -> 0
        }

    companion object {
        val FULL = ALL_TARGETS
        fun ofNumber(number: Int): TestStep =
            entries.firstOrNull { it.number == number } ?: FULL
    }
}
