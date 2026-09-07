package com.macromobile.imagemacro.automation

import com.macromobile.imagemacro.vision.NamedMatch

/** 매크로 실행 상태. */
enum class RunState {
    /** 아무 것도 실행하고 있지 않음. */
    IDLE,

    /** 권한 확인·화면 캡처 준비 중. */
    PREPARING,

    /** 실행 중. */
    RUNNING,

    /** 일시정지. 재개하면 멈춘 단계부터 이어서 진행한다. */
    PAUSED,

    /** 타겟을 찾아서 멈춘 상태. 사용자가 재개하거나 종료할 때까지 기다린다. */
    TARGET_FOUND,

    /** 사용자가 중지했거나 반복이 끝난 상태. */
    STOPPED,

    /** 오류로 멈춘 상태. */
    ERROR,
    ;

    val koreanLabel: String
        get() = when (this) {
            IDLE -> "대기 중"
            PREPARING -> "준비 중"
            RUNNING -> "실행 중"
            PAUSED -> "일시정지"
            TARGET_FOUND -> "타겟 발견"
            STOPPED -> "중지됨"
            ERROR -> "오류"
        }

    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == PREPARING
}

/** 타겟을 찾았을 때의 상세 정보. */
data class TargetFoundInfo(
    val targetName: String,
    val score: Float,
    /** 화면에서 찾은 모든 타겟(디버그 표시용). */
    val matches: List<NamedMatch>,
    /** 발견 순간의 화면을 저장한 파일 경로. 저장하지 않았으면 null. */
    val screenshotPath: String? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val foundAt: Long = System.currentTimeMillis(),
)

/** 실행 기록 한 줄. */
data class RunLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: Level = Level.INFO,
) {
    enum class Level { INFO, SUCCESS, WARN, ERROR }
}

/**
 * 매크로 실행 상황 전체.
 *
 * UI 가 죽었다 살아나도 이 값만 보면 지금 무슨 일이 벌어지고 있는지 알 수 있다.
 */
data class MacroStatus(
    val state: RunState = RunState.IDLE,
    val macroId: String = "",
    val macroName: String = "",
    /** 지금 실행 중인 단계 번호(0부터). */
    val stepIndex: Int = -1,
    val stepName: String = "",
    val totalSteps: Int = 0,
    /** 지금이 몇 번째 반복인지(1부터). */
    val cycle: Int = 0,
    val totalCycles: Int = 0,
    val startedAt: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastMatchScore: Float = 0f,
    val targetFound: TargetFoundInfo? = null,
    /** 사용자에게 보여줄 오류 메시지. 개발자용 stack trace 는 담지 않는다. */
    val errorMessage: String? = null,
    val log: List<RunLogEntry> = emptyList(),
) {
    val elapsedMs: Long get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    val progressLabel: String
        get() = when {
            totalSteps <= 0 -> "-"
            stepIndex < 0 -> "0 / $totalSteps"
            else -> "${stepIndex + 1} / $totalSteps"
        }

    val cycleLabel: String
        get() = when {
            totalCycles < 0 -> "$cycle / ∞"
            totalCycles == 0 -> "$cycle"
            else -> "$cycle / $totalCycles"
        }
}

/** 단계 실행 결과. */
sealed interface StepOutcome {
    /** 정상 완료. 다음 단계로. */
    data object Continue : StepOutcome

    /** 이번 반복을 버리고 매크로를 처음부터 다시. */
    data class Restart(val reason: String) : StepOutcome

    /** 매크로 전체 중단. */
    data class Abort(val reason: String) : StepOutcome

    /** 매크로를 성공으로 끝냄(STOP_MACRO 단계 등). */
    data class Finish(val reason: String) : StepOutcome

    /** 타겟을 찾아 즉시 중지. */
    data class TargetHit(val info: TargetFoundInfo) : StepOutcome
}
