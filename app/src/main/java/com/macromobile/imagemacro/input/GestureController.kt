package com.macromobile.imagemacro.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.macromobile.imagemacro.service.MacroAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/** 제스처 실행 결과. 실패 이유를 사용자에게 그대로 보여줄 수 있게 문장으로 담는다. */
sealed interface GestureOutcome {
    data object Success : GestureOutcome
    data class Failed(val reason: String) : GestureOutcome
}

/**
 * AccessibilityService 의 `dispatchGesture()` 로 화면을 터치한다.
 *
 * ROOT 나 ADB 없이 다른 앱을 터치할 수 있는 공식 방법이며, 접근성 서비스가 꺼져 있으면
 * 아무 것도 할 수 없으므로 명확한 실패 사유를 돌려준다.
 */
class GestureController {

    /** 터치 좌표에 줄 흔들림(px). 0 이면 정확히 그 좌표를 누른다. */
    var jitterPx: Int = 0

    private fun service(): MacroAccessibilityService? = MacroAccessibilityService.instance

    suspend fun tap(x: Int, y: Int, holdMs: Long = 60L): GestureOutcome {
        val svc = service() ?: return notConnected()
        val (jx, jy) = jitter(x, y)
        val path = Path().apply { moveTo(jx.toFloat(), jy.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs.coerceAtLeast(1L))
        return dispatch(svc, GestureDescription.Builder().addStroke(stroke).build(), "터치")
    }

    suspend fun longPress(x: Int, y: Int, holdMs: Long = 600L): GestureOutcome = tap(x, y, holdMs)

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 400L,
    ): GestureOutcome {
        val svc = service() ?: return notConnected()
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1L, 60_000L))
        return dispatch(svc, GestureDescription.Builder().addStroke(stroke).build(), "스와이프")
    }

    /** 전역 동작(뒤로가기·홈 등). */
    fun performGlobalAction(action: Int): GestureOutcome {
        val svc = service() ?: return notConnected()
        return if (svc.performGlobalAction(action)) {
            GestureOutcome.Success
        } else {
            GestureOutcome.Failed("시스템 동작을 실행하지 못했습니다.")
        }
    }

    private fun jitter(x: Int, y: Int): Pair<Int, Int> {
        if (jitterPx <= 0) return x to y
        val dx = Random.nextInt(-jitterPx, jitterPx + 1)
        val dy = Random.nextInt(-jitterPx, jitterPx + 1)
        return (x + dx).coerceAtLeast(0) to (y + dy).coerceAtLeast(0)
    }

    private suspend fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        label: String,
    ): GestureOutcome = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(GestureOutcome.Success)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) {
                    cont.resume(
                        GestureOutcome.Failed(
                            "$label 동작이 취소되었습니다. 다른 앱이 화면을 잠그고 있거나 " +
                                "보안 화면일 수 있습니다.",
                        ),
                    )
                }
            }
        }
        val accepted = try {
            service.dispatchGesture(gesture, callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "$label 전달 실패", e)
            false
        }
        if (!accepted && cont.isActive) {
            cont.resume(GestureOutcome.Failed("$label 동작을 전달하지 못했습니다."))
        }
    }

    private fun notConnected() = GestureOutcome.Failed(
        "접근성 서비스가 연결되어 있지 않습니다. 설정에서 'Image Macro 자동 조작'을 켜주세요.",
    )

    companion object {
        private const val TAG = "GestureController"

        /** `dispatchGesture` 는 API 24 부터 쓸 수 있다(이 앱의 minSdk 는 26). */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
