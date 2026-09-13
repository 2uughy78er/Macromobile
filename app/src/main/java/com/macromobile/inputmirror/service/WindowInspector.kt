package com.macromobile.inputmirror.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import com.macromobile.inputmirror.model.Region

/**
 * 지금 화면에 떠 있는 창 하나.
 *
 * 여기 담긴 값은 전부 **시스템이 실제로 알려준 값**이다. 짐작하거나 계산해 넣은 것이 없다.
 */
data class WindowSnapshot(
    val id: Int,
    val bounds: Region,
    val packageName: String?,
    val title: String?,
    val type: Int,
    val layer: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
) {
    val typeLabel: String
        get() = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
            else -> "TYPE_$type"
        }

    /** 미러링 대상이 될 수 있는 창인가. 앱 창이면서 우리 오버레이가 아닌 것. */
    fun isCandidate(ownPackage: String): Boolean =
        type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            bounds.isUsable() &&
            packageName != ownPackage

    fun describe(): String = buildString {
        append(typeLabel.padEnd(13))
        append("L${bounds.left} T${bounds.top} R${bounds.right} B${bounds.bottom}")
        append("  (${bounds.width}×${bounds.height})")
        append("  layer=$layer")
        if (isActive) append("  ACTIVE")
        if (isFocused) append("  FOCUSED")
        append("  ").append(packageName ?: "(패키지 모름)")
        title?.let { append("  \"").append(it).append('"') }
    }
}

/**
 * 화면에 떠 있는 창들을 읽는다.
 *
 * ## 이게 가능한 이유
 *
 * 일반 앱은 다른 앱의 창 위치를 알 수 없지만, **접근성 서비스는 알 수 있다.**
 * `AccessibilityService.getWindows()` 가 화면의 창 목록을 주고, 각
 * `AccessibilityWindowInfo.getBoundsInScreen()` 이 화면 좌표 경계를 준다.
 * 서비스 설정에 `flagRetrieveInteractiveWindows` 와 `canRetrieveWindowContent` 가
 * 켜져 있어야 한다(둘 다 이미 켜져 있다).
 *
 * ## 이게 완전하지 않은 이유
 *
 * - 패키지명은 창 자체가 아니라 `getRoot()?.packageName` 에서 온다. 창 내용을 읽을 수
 *   없는 앱(FLAG_SECURE 등)에서는 null 이 된다. 그래도 **경계값은 대개 나온다.**
 * - 게임이 전체화면 몰입 모드면 경계가 기대와 다를 수 있다.
 *
 * 그래서 이 클래스는 **참고 자료**다. 최종 영역은 사용자가 직접 지정한 값을 쓰고,
 * 여기서 읽은 값은 "이 창을 그대로 쓰기" 버튼으로 채워 넣는 데만 쓴다.
 */
object WindowInspector {

    /** 지금 화면의 창 목록. 위에 있는 창이 먼저 온다. */
    fun snapshot(service: AccessibilityService): List<WindowSnapshot> {
        val windows = runCatching { service.windows }.getOrNull() ?: return emptyList()
        return windows.mapNotNull { window ->
            runCatching { toSnapshot(window) }.getOrNull()
        }
    }

    /** 미러링 영역 후보만. 앱 창이면서 우리 자신이 아닌 것. */
    fun candidates(service: AccessibilityService): List<WindowSnapshot> {
        val own = service.packageName ?: ""
        return snapshot(service).filter { it.isCandidate(own) }
    }

    private fun toSnapshot(window: AccessibilityWindowInfo): WindowSnapshot {
        val rect = Rect()
        window.getBoundsInScreen(rect)

        // 패키지명은 창이 아니라 루트 노드에 있다. 못 읽어도 경계는 쓸 수 있으므로
        // 실패를 조용히 null 로 두고 계속한다.
        val packageName = runCatching {
            window.root?.let { root ->
                val name = root.packageName?.toString()
                root.recycle()
                name
            }
        }.getOrNull()

        return WindowSnapshot(
            id = window.id,
            bounds = Region(rect.left, rect.top, rect.right, rect.bottom),
            packageName = packageName,
            title = runCatching { window.title?.toString() }.getOrNull(),
            type = window.type,
            layer = window.layer,
            isActive = window.isActive,
            isFocused = window.isFocused,
        )
    }

    /** 화면 전체를 사람이 읽을 수 있게 덤프한다. Phase 0 측정에 쓴다. */
    fun dump(service: AccessibilityService): String {
        val windows = snapshot(service)
        if (windows.isEmpty()) {
            return "창 목록을 읽지 못했습니다. 접근성 서비스가 연결되어 있는지 확인하세요."
        }
        return buildString {
            appendLine("창 ${windows.size}개 (위에 있는 것부터)")
            windows.forEach { appendLine("  " + it.describe()) }
        }.trimEnd()
    }
}
