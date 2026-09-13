package com.macromobile.inputmirror.model

/** 입력을 언제 대상에 보낼지. */
enum class MirrorMode {
    /**
     * 손가락이 움직이는 동안 구간을 이어 보낸다. 지연이 짧지만, 앞 구간이 끝나야
     * 다음을 보낼 수 있어 빠른 동작에서는 점이 일부 뭉쳐진다.
     */
    STREAMING,

    /**
     * 손가락을 뗀 뒤 경로 전체를 한 번에 보낸다. 경로는 정확하지만 동작이 끝난
     * 다음에야 대상이 움직인다.
     */
    BATCH,
    ;

    val koreanLabel: String
        get() = when (this) {
            STREAMING -> "즉시 전송 (움직이는 동안)"
            BATCH -> "완료 후 전송 (손 뗀 뒤)"
        }

    val description: String
        get() = when (this) {
            STREAMING -> "지연이 짧습니다. 실제 게임 미러링에는 이쪽이 맞습니다."
            BATCH -> "경로가 정확합니다. 좌표 변환이 맞는지 확인할 때 좋습니다."
        }
}

/**
 * 대상이 여럿일 때 어떻게 보낼지. 요구사항 6단계의 MODE A/B/C.
 *
 * 어느 방식이 실제로 통하는지 비교해서 확인하기 위해 나눠 둔다.
 */
enum class DispatchMode {
    /** MODE A — 켜둔 대상 중 **첫 번째 하나**에만 보낸다. 가장 단순해서 기준이 된다. */
    SINGLE,

    /** MODE B — 대상마다 제스처를 **따로, 차례로** 보낸다. 앞의 것이 끝나야 다음을 보낸다. */
    SEQUENTIAL,

    /** MODE C — 스트로크를 대상 수만큼 만들어 **하나의 제스처**로 한 번에 보낸다. */
    COMBINED,
    ;

    val koreanLabel: String
        get() = when (this) {
            SINGLE -> "MODE A — 대상 1개만"
            SEQUENTIAL -> "MODE B — 하나씩 차례로"
            COMBINED -> "MODE C — 한 번에 묶어서"
        }

    val description: String
        get() = when (this) {
            SINGLE -> "가장 단순합니다. 이것도 안 되면 좌표나 권한 문제입니다."
            SEQUENTIAL -> "제스처를 대상 수만큼 보냅니다. 뒤의 것이 앞의 것을 취소하는지 확인합니다."
            COMBINED -> "한 제스처에 손가락 여러 개로 보냅니다. 동시 입력에 가장 가깝습니다."
        }
}

/** 앱 전역 설정. */
data class MirrorSettings(
    val mode: MirrorMode = MirrorMode.STREAMING,
    val dispatchMode: DispatchMode = DispatchMode.COMBINED,
    /** 마스터 입력 후 대상에 보내기까지 일부러 두는 지연(ms). */
    val inputDelayMs: Long = 0L,
    val fitMode: FitMode = FitMode.FIT,
    /** 대상별 사용 여부. 인덱스 0 = TARGET 1. */
    val enabledTargets: List<Boolean> = listOf(true, true, true),
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val saveLogToFile: Boolean = true,
) {
    companion object {
        val DELAY_CHOICES = listOf(0L, 10L, 20L, 50L, 100L)
    }
}
