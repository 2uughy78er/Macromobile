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

/** 앱 전역 설정. */
data class MirrorSettings(
    val mode: MirrorMode = MirrorMode.STREAMING,
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
