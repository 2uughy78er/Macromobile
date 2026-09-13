package com.macromobile.inputmirror.mirror

/**
 * 미러링 상태.
 *
 * ```
 * IDLE ──START──▶ PREPARING ──점검 통과──▶ RUNNING ◀──RESUME──┐
 *   ▲                 │                      │               │
 *   │                 └──점검 실패──▶ IDLE    └────PAUSE────▶ PAUSED
 *   └──────────────────── STOP ◀────────────────────────────────┘
 * ```
 */
enum class MirrorState {
    IDLE,
    PREPARING,
    RUNNING,
    PAUSED,
    STOPPED,
    ;

    /** 지금 입력을 대상에 전달하는가. PAUSED 는 전달하지 않는다. */
    val deliversInput: Boolean get() = this == RUNNING

    val koreanLabel: String
        get() = when (this) {
            IDLE -> "대기"
            PREPARING -> "준비 중"
            RUNNING -> "실행 중"
            PAUSED -> "일시정지"
            STOPPED -> "정지됨"
        }
}

/**
 * 실패 이유.
 *
 * "안 됐다"만으로는 고칠 수 없다. 원인이 다르면 사용자가 할 일도 다르므로 구분해서
 * 기록하고 화면에 그대로 보여준다. 게임이 입력을 거부하는 경우도 앱의 버그로 뭉뚱그리지
 * 않고 제 이름으로 남긴다.
 */
enum class MirrorError {
    ACCESSIBILITY_NOT_CONNECTED,
    TARGET_NOT_AVAILABLE,
    INVALID_COORDINATE,
    GESTURE_REJECTED,
    GESTURE_CANCELLED,
    WINDOW_CHANGED,
    MASTER_NOT_FOUND,
    SERVICE_ERROR,

    /**
     * 대상이 주입 입력을 받아주지 않는다.
     *
     * `FLAG_SECURE`, `setFilterTouchesWhenObscured`, 안티치트, 자체 입력 시스템 등
     * 대상 쪽 사정이다. 우리 쪽 버그가 아니지만 **숨기지 않고** 이 이름으로 남긴다.
     */
    TARGET_INPUT_REJECTED_OR_UNSUPPORTED,

    UNKNOWN_ERROR,
    ;

    val koreanLabel: String
        get() = when (this) {
            ACCESSIBILITY_NOT_CONNECTED -> "접근성 서비스가 연결되어 있지 않습니다."
            TARGET_NOT_AVAILABLE -> "보낼 수 있는 TARGET 이 없습니다."
            INVALID_COORDINATE -> "좌표를 변환하지 못했습니다."
            GESTURE_REJECTED -> "시스템이 제스처 접수를 거부했습니다."
            GESTURE_CANCELLED -> "시스템이 제스처를 취소했습니다."
            WINDOW_CHANGED -> "화면 구성이 바뀌어 영역을 다시 확인해야 합니다."
            MASTER_NOT_FOUND -> "MASTER 영역이 지정되지 않았습니다."
            SERVICE_ERROR -> "서비스 내부 오류입니다."
            TARGET_INPUT_REJECTED_OR_UNSUPPORTED ->
                "대상이 주입 입력을 받지 않습니다. 게임 쪽 보호 기능일 수 있습니다."
            UNKNOWN_ERROR -> "알 수 없는 오류입니다."
        }
}

/** 화면과 로그에 함께 쓰는 실패 기록. */
data class MirrorFailure(
    val error: MirrorError,
    val detail: String? = null,
    val targetName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun describe(): String = buildString {
        targetName?.let { append(it).append(": ") }
        append(error.name)
        append(" — ").append(error.koreanLabel)
        detail?.takeIf { it.isNotBlank() }?.let { append("  (").append(it).append(')') }
    }
}
