package com.macromobile.inputmirror.diag

/**
 * 테스트 화면이 열리기까지 거치는 단계.
 *
 * 크래시가 났을 때 "어디까지 갔다가 죽었는지"를 추측이 아니라 기록으로 알기 위해 둔다.
 * 마지막으로 기록된 단계가 곧 크래시 직전 지점이다.
 *
 * 이름은 요청받은 문자열을 그대로 쓴다. 로그 파일과 화면에도 이 이름이 그대로 나온다.
 */
enum class DiagStage(val label: String) {
    /** 메인 화면에서 "테스트 화면 열기" 를 눌렀다. */
    TEST_SCREEN_CLICK("테스트 화면 열기 버튼 눌림"),

    /** 내비게이션 호출 직전. 여기까지 찍히고 멈추면 화면 전환 자체가 문제다. */
    TEST_SCREEN_NAVIGATION_START("테스트 화면으로 이동 시작"),

    /** Activity 가 살아 있는지 확인. 이 앱은 Activity 하나를 재사용한다. */
    TEST_SCREEN_ACTIVITY_CREATE("테스트 화면 Activity 확인"),

    /** 테스트 화면 Composable 진입. */
    TEST_SCREEN_COMPOSE_START("테스트 화면 컴포즈 시작"),

    /** 접근성 서비스 연결 상태 조회. */
    ACCESSIBILITY_SERVICE_CHECK("접근성 서비스 상태 확인"),

    /** DisplayMetrics 읽기. */
    DISPLAY_METRICS_READ("디스플레이 메트릭 읽기"),

    /** WindowMetrics / 인셋 읽기. 컨텍스트 종류를 잘못 쓰면 여기서 죽는다. */
    WINDOW_METRICS_READ("윈도우 메트릭 읽기"),

    /** 4분할 오버레이 뷰 생성 및 부착. 이 앱에는 시스템 오버레이가 없고 앱 안의 뷰를 쓴다. */
    OVERLAY_INITIALIZE("4분할 오버레이 뷰 생성"),

    /** 제스처 전송기 초기화(영역 등록, 대상 구성). */
    GESTURE_CONTROLLER_INITIALIZE("제스처 전송기 초기화"),

    /** 테스트 화면이 끝까지 떴다. 여기까지 찍히면 화면 진입은 성공이다. */
    TEST_SCREEN_READY("테스트 화면 준비 완료"),
    ;
}

/** 한 단계의 결과. */
enum class DiagOutcome { STARTED, OK, FAILED, SKIPPED }
