package com.macromobile.inputmirror.model

/** 입력을 언제 대상에 보낼지. */
enum class MirrorMode {
    /**
     * 끌기로 판정되는 **즉시** 그때까지의 경로 전체를 열고, 이후 구간을 이어 보낸다.
     *
     * 판정 전(PENDING) 구간은 버리지 않고 들고 있다가 열 때 함께 보낸다. 그래서
     * **입력 손실은 없고 지연만 생긴다.** 지연은 손가락이 임계값을 넘기까지 걸린 시간이며
     * 그 값은 로그에 그대로 남는다.
     */
    STREAMING,

    /**
     * 손가락을 뗀 뒤 판정하고 경로 전체를 한 번에 보낸다. 경로는 정확하지만 동작이 끝난
     * 다음에야 대상이 움직인다.
     */
    BATCH,
    ;

    val koreanLabel: String
        get() = when (this) {
            STREAMING -> "즉시 전송 (끌기로 판정되는 즉시)"
            BATCH -> "완료 후 전송 (손 뗀 뒤 판정)"
        }

    val description: String
        get() = when (this) {
            STREAMING -> "지연이 짧습니다. 실제 게임 미러링에는 이쪽이 맞습니다. " +
                "누르기는 손을 뗄 때 한 번에 갑니다(그래야 끌기와 구분됩니다)."
            BATCH -> "경로가 정확합니다. 좌표 변환이나 판정을 확인할 때 좋습니다."
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
    /**
     * 누르기와 끌기를 가르는 이동거리(**dp**).
     *
     * 픽셀이 아니라 dp 다. 같은 10px 이라도 고밀도 화면에서는 훨씬 짧은 거리이기 때문에
     * 픽셀로 고정하면 기기마다 판정이 달라진다. 실제 비교는 dp × density 로 환산한
     * 픽셀값으로 한다.
     */
    val dragThresholdDp: Float = DEFAULT_DRAG_THRESHOLD_DP,
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

        /** 기본 임계값. 안드로이드의 기본 터치 슬롭(8dp)보다 조금 크게 잡았다. */
        const val DEFAULT_DRAG_THRESHOLD_DP = 12f

        /** 설정에서 고를 수 있는 범위. */
        const val MIN_DRAG_THRESHOLD_DP = 4f
        const val MAX_DRAG_THRESHOLD_DP = 32f
    }
}
