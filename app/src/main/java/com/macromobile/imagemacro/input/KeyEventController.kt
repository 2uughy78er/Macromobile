package com.macromobile.imagemacro.input

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.macromobile.imagemacro.model.KeyAction

/**
 * 뒤로가기·홈 같은 시스템 동작을 실행한다.
 *
 * 루팅 없이 임의의 KeyEvent 를 주입할 수는 없으므로, AccessibilityService 가 제공하는
 * 전역 동작(Global Action)만 지원한다. PC 버전의 `adb shell input keyevent` 를 대신한다.
 */
class KeyEventController(private val gestures: GestureController) {

    fun perform(action: KeyAction): GestureOutcome {
        val code = when (action) {
            KeyAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            KeyAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            KeyAction.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return GestureOutcome.Failed("화면 잠금은 Android 9 이상에서만 지원됩니다.")
                }
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
        }
        return gestures.performGlobalAction(code)
    }
}
