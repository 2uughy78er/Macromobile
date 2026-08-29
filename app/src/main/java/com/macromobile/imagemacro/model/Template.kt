package com.macromobile.imagemacro.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 사용자가 직접 등록한 템플릿 이미지의 메타데이터.
 *
 * 이미지 파일 자체는 앱 내부 저장소(`templates/`)에 저장하고 여기에는 파일 이름만 담는다.
 * 앱에는 어떤 기본 템플릿 이미지도 포함하지 않는다 — 전부 사용자가 등록한다.
 */
@Serializable
data class Template(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** `templates/` 폴더 안의 PNG 파일 이름. */
    val fileName: String = "",
    /** 이 템플릿을 잘라낸 화면의 해상도. 실행 화면과 다르면 자동 보정한다. */
    val referenceScreenWidth: Int = 0,
    val referenceScreenHeight: Int = 0,
    /** 캡처 당시 이 템플릿이 있던 화면 위치(기준 해상도 좌표). 클릭 offset 계산과 ROI 추천에 쓴다. */
    val sourceRegion: Roi? = null,
    /** 기본 검색 영역. null 이면 화면 전체. */
    val searchRegion: Roi? = null,
    val threshold: Float = DEFAULT_THRESHOLD,
    /** 매칭 중심 기준 클릭 보정값(기준 해상도 픽셀). */
    val clickOffsetX: Int = 0,
    val clickOffsetY: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.85f
    }
}

/**
 * 사용자가 등록한 타겟 카드 이미지.
 *
 * 구조는 템플릿과 같지만 "발견 즉시 매크로 중지" 판정에만 쓰이므로 따로 관리한다.
 */
@Serializable
data class Target(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** `targets/` 폴더 안의 PNG 파일 이름. */
    val fileName: String = "",
    val referenceScreenWidth: Int = 0,
    val referenceScreenHeight: Int = 0,
    val searchRegion: Roi? = null,
    val threshold: Float = DEFAULT_TARGET_THRESHOLD,
    /**
     * 오탐 방지용 크기 제한. 매칭된 영역의 너비가 화면 너비에서 차지하는 비율이
     * 이 값보다 작으면 무시한다. 0 이면 제한하지 않는다.
     */
    val minMatchWidthRatio: Float = 0f,
    /** 매칭 영역 너비 비율이 이 값보다 크면 무시한다. 0 이면 제한하지 않는다. */
    val maxMatchWidthRatio: Float = 0f,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_TARGET_THRESHOLD = 0.90f
    }
}

/** 타겟 여러 개를 한 번에 판정하는 방식. */
@Serializable
enum class TargetMatchMode {
    /** 하나라도 발견되면 성공. */
    ANY,

    /** 등록된 타겟 전부가 발견되어야 성공. */
    ALL,

    /** [TargetSettings.minCount] 개 이상 발견되면 성공. */
    COUNT,
}

/** 타겟 판정 공통 설정. 매크로 단위로 저장한다. */
@Serializable
data class TargetSettings(
    val mode: TargetMatchMode = TargetMatchMode.ANY,
    val minCount: Int = 1,
    val threshold: Float = Target.DEFAULT_TARGET_THRESHOLD,
    val roi: Roi? = null,
    /** 화면 전환 애니메이션이 끝나기를 기다리는 시간(ms). */
    val settleDelayMs: Long = 1_200L,
    /** 매크로 단계와 별개로 백그라운드에서 계속 타겟을 감시할지. */
    val monitorEnabled: Boolean = false,
    val monitorIntervalMs: Long = 500L,
    /** 이 횟수만큼 연속으로 발견되어야 "발견"으로 확정한다. 1 이면 즉시 중지. */
    val requiredConsecutiveMatches: Int = 1,
    /** 타겟을 찾으면 화면을 저장할지. */
    val saveScreenshotOnFound: Boolean = true,
)
