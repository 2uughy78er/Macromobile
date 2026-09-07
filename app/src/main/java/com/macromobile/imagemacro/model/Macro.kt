package com.macromobile.imagemacro.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** 매크로가 지원하는 동작 종류. */
@Serializable
enum class ActionType {
    /** 지정 시간만큼 대기. */
    WAIT,

    /** 지정 좌표를 터치. */
    TAP,

    /** 이미지가 나타날 때까지 대기(터치하지 않음). */
    WAIT_FOR_IMAGE,

    /** 이미지가 나타나면 발견된 위치를 터치. */
    WAIT_AND_TAP,

    /** 짧게 확인해서 이미지가 있으면 터치, 없으면 그냥 다음 단계로. */
    TAP_IF_FOUND,

    /** 목표 이미지가 나타날 때까지 지정 위치를 반복 터치. */
    TAP_UNTIL_IMAGE,

    /** 지정 이미지가 사라질 때까지 지정 위치를 반복 터치. */
    TAP_UNTIL_IMAGE_DISAPPEARS,

    /** 스와이프. */
    SWIPE,

    /** 텍스트 입력. */
    TEXT_INPUT,

    /** 키 이벤트(뒤로가기 등). */
    KEY_EVENT,

    /** 현재 화면을 파일로 저장. */
    SCREENSHOT,

    /** 화면의 글자를 읽어 변수에 저장. */
    OCR,

    /** 등록된 타겟이 화면에 있는지 판정. */
    TARGET_CHECK,

    /** 매크로 중지. */
    STOP_MACRO,
    ;

    val koreanLabel: String
        get() = when (this) {
            WAIT -> "대기"
            TAP -> "좌표 터치"
            WAIT_FOR_IMAGE -> "이미지 발견 대기"
            WAIT_AND_TAP -> "이미지 발견 후 터치"
            TAP_IF_FOUND -> "있으면 터치"
            TAP_UNTIL_IMAGE -> "이미지 나올 때까지 연타"
            TAP_UNTIL_IMAGE_DISAPPEARS -> "이미지 사라질 때까지 연타"
            SWIPE -> "스와이프"
            TEXT_INPUT -> "텍스트 입력"
            KEY_EVENT -> "키 이벤트"
            SCREENSHOT -> "스크린샷"
            OCR -> "글자 읽기(OCR)"
            TARGET_CHECK -> "타겟 검사"
            STOP_MACRO -> "매크로 중지"
        }

    /** 이 동작이 템플릿 이미지를 필요로 하는지. */
    val usesTemplates: Boolean
        get() = this in setOf(
            WAIT_FOR_IMAGE, WAIT_AND_TAP, TAP_IF_FOUND,
            TAP_UNTIL_IMAGE, TAP_UNTIL_IMAGE_DISAPPEARS,
        )
}

/** 단계가 시간 안에 완료되지 못했을 때의 처리 방식. */
@Serializable
enum class OnTimeout {
    /** 이번 반복을 버리고 매크로를 처음부터 다시 시작. */
    RESTART,

    /** 이 단계를 건너뛰고 다음 단계로. */
    SKIP,

    /** 매크로 전체를 중단. */
    STOP,
    ;

    val koreanLabel: String
        get() = when (this) {
            RESTART -> "처음부터 다시"
            SKIP -> "건너뛰기"
            STOP -> "중단"
        }
}

/** 여러 템플릿을 한 단계에서 함께 쓸 때의 판정 방식. */
@Serializable
enum class TemplateMatchMode {
    /** 하나라도 찾으면 성공. */
    ANY,

    /** 전부 찾아야 성공. */
    ALL,

    /** [MacroStep.minMatchCount] 개 이상 찾으면 성공. */
    COUNT,
    ;

    val koreanLabel: String
        get() = when (this) {
            ANY -> "하나라도(OR)"
            ALL -> "모두(AND)"
            COUNT -> "N개 이상(COUNT)"
        }
}

/** 키 이벤트 종류. AccessibilityService 의 전역 동작으로 처리한다. */
@Serializable
enum class KeyAction {
    BACK, HOME, RECENTS, NOTIFICATIONS, LOCK_SCREEN;

    val koreanLabel: String
        get() = when (this) {
            BACK -> "뒤로가기"
            HOME -> "홈"
            RECENTS -> "최근 앱"
            NOTIFICATIONS -> "알림창 열기"
            LOCK_SCREEN -> "화면 잠금"
        }
}

/**
 * 매크로의 한 단계.
 *
 * 모든 동작 종류가 하나의 클래스를 공유한다. 종류별로 쓰이지 않는 필드는 무시된다.
 * (JSON 스키마를 단순하게 유지해 저장/편집/마이그레이션을 쉽게 하기 위함)
 */
@Serializable
data class MacroStep(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType = ActionType.WAIT_AND_TAP,
    val name: String = "",
    val enabled: Boolean = true,

    // ---- 이미지 관련 ----
    /** 이 단계에서 찾을 템플릿 ID 목록. */
    val templateIds: List<String> = emptyList(),
    val templateMatchMode: TemplateMatchMode = TemplateMatchMode.ANY,
    val minMatchCount: Int = 1,
    /** null 이면 템플릿 자체의 threshold 를 쓴다. */
    val threshold: Float? = null,
    /** null 이면 템플릿 자체의 searchRegion 을 쓴다. */
    val roi: Roi? = null,

    // ---- 시간 ----
    /** 이미지를 기다리는 최대 시간(ms). */
    val timeoutMs: Long = 60_000L,
    /** 화면을 다시 검사하는 간격(ms). 0 이면 전역 설정을 쓴다. */
    val pollIntervalMs: Long = 0L,
    /** 단계가 끝난 뒤 쉬는 시간(ms). */
    val afterDelayMs: Long = 600L,
    /** WAIT 단계의 대기 시간(ms). */
    val waitMs: Long = 1_000L,
    val onTimeout: OnTimeout = OnTimeout.RESTART,

    // ---- 좌표 ----
    /** TAP / TAP_UNTIL_* 에서 터치할 위치(기준 해상도 좌표). */
    val point: RefPoint? = null,
    /**
     * TAP 을 누르고 있는 시간(ms). 0 이면 짧게 톡 누른다.
     *
     * 길게 누르기(롱프레스)를 표현할 때 쓴다. 동작 녹화가 이 값을 채운다.
     */
    val tapHoldMs: Long = 0L,
    /** 이미지 매칭 중심에 더할 보정값. 단계에서 지정하면 템플릿 값보다 우선한다. */
    val clickOffsetX: Int = 0,
    val clickOffsetY: Int = 0,
    val useTemplateClickOffset: Boolean = true,

    // ---- 스와이프 ----
    val swipeStart: RefPoint? = null,
    val swipeEnd: RefPoint? = null,
    val swipeDurationMs: Long = 400L,

    // ---- 연타 ----
    /** TAP_UNTIL_* 의 터치 간격(ms). */
    val repeatIntervalMs: Long = 700L,
    /** TAP_UNTIL_IMAGE 의 목표 템플릿 ID. */
    val untilTemplateIds: List<String> = emptyList(),

    // ---- 텍스트 ----
    /** `@변수명`, `@random`, `@random:8` 형식을 지원한다. */
    val text: String = "",
    /** 입력 전에 기존 내용을 지울지. */
    val clearBeforeInput: Boolean = true,
    val keyAction: KeyAction = KeyAction.BACK,

    // ---- OCR ----
    /** 인식 결과를 담을 변수 이름. */
    val storeVariable: String = "",
    /** 허용할 문자만 남긴다. 비우면 제한 없음. */
    val charset: String = "",
    /** 결과 길이가 이 값과 다르면 실패로 본다. 0 이면 검사하지 않음. */
    val expectedLength: Int = 0,
    val minConfidence: Float = 0.7f,
    /** 한국어 인식기를 쓸지. 끄면 라틴 문자 인식기를 쓴다. */
    val ocrKorean: Boolean = false,

    // ---- 타겟 ----
    /** TARGET_CHECK 에서 쓸 타겟 ID. 비우면 매크로의 모든 타겟을 검사한다. */
    val targetIds: List<String> = emptyList(),
    val targetMatchMode: TargetMatchMode? = null,
    val targetMinCount: Int = 1,
    /** 타겟 판정 실패 시의 처리. 성공하면 항상 다음 단계로 간다. */
    val onTargetMissing: OnTimeout = OnTimeout.RESTART,
) {
    /** UI 에 보여줄 이름. 사용자가 이름을 비워두면 동작 이름을 쓴다. */
    fun displayName(index: Int): String =
        name.ifBlank { "${index + 1}. ${type.koreanLabel}" }
}

/** 매크로 전체 반복 방식. */
@Serializable
data class RepeatSettings(
    /** -1 이면 무한 반복. */
    val count: Int = 1,
    /** 한 바퀴를 성공으로 끝내면 멈출지. */
    val stopOnSuccess: Boolean = true,
    /** 타겟을 찾으면 멈출지. */
    val stopOnTargetFound: Boolean = true,
    /** 단계가 실패하면 처음부터 다시 시작할지. false 면 매크로를 중단한다. */
    val restartOnFailure: Boolean = true,
    /** 한 바퀴가 끝난 뒤 쉬는 시간(ms). */
    val cycleDelayMs: Long = 1_000L,
) {
    val isInfinite: Boolean get() = count < 0
}

/**
 * 사용자가 만든 매크로 한 개.
 *
 * 앱은 어떤 기본 매크로도 포함하지 않는다. 목록은 처음에 비어 있고,
 * 단계·이미지·타겟은 전부 사용자가 등록한다.
 */
@Serializable
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    /** 이 매크로의 좌표/ROI 가 기준으로 삼는 해상도. 보통 등록 당시의 화면 크기. */
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0,
    val steps: List<MacroStep> = emptyList(),
    val templates: List<Template> = emptyList(),
    val targets: List<Target> = emptyList(),
    val targetSettings: TargetSettings = TargetSettings(),
    val repeat: RepeatSettings = RepeatSettings(),
    /** 매크로 시작 시 초기화할 변수. */
    val initialVariables: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun template(id: String): Template? = templates.firstOrNull { it.id == id }
    fun target(id: String): Target? = targets.firstOrNull { it.id == id }

    val enabledSteps: List<MacroStep> get() = steps.filter { it.enabled }
    val enabledTargets: List<Target> get() = targets.filter { it.enabled }

    fun displayName(): String = name.ifBlank { "이름 없는 매크로" }
}
