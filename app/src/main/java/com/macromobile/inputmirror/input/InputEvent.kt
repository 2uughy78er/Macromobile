package com.macromobile.inputmirror.input

/** 터치 한 점(실제 화면 픽셀 + 발생 시각). */
data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
)

/** 터치가 어떤 단계인지. 화면 표시와 로그에 그대로 쓴다. */
enum class TouchPhase {
    DOWN, MOVE, UP, CANCEL;

    val label: String
        get() = when (this) {
            DOWN -> "ACTION_DOWN"
            MOVE -> "ACTION_MOVE"
            UP -> "ACTION_UP"
            CANCEL -> "ACTION_CANCEL"
        }
}

/**
 * 미러링 결과 한 줄. 화면 표시와 로그 파일에 함께 쓴다.
 *
 * [gestureId] 와 [gestureType] 을 함께 담는다. 한 제스처가 여러 구간으로 나뉘어 전송되어도
 * 같은 번호로 묶이고, 그 제스처가 TAP 으로 판정됐는지 DRAG 로 판정됐는지가 기록에 남는다.
 */
data class MirrorRecord(
    val timestamp: Long,
    val gestureId: Long,
    val gestureType: GestureType,
    val phase: TouchPhase,
    val masterX: Float,
    val masterY: Float,
    /** 대상 이름 → 변환된 좌표. 변환 불가면 값이 없다. */
    val targets: Map<String, MappedPoint>,
    /** 마스터 입력 시각과 실제 전송 시각의 차이(ms). */
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null,
)
