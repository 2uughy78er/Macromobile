package com.macromobile.inputmirror.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.model.ScreenFingerprint

/**
 * 접근성 오버레이 창을 관리한다.
 *
 * ## 왜 접근성 오버레이인가
 *
 * 일반 앱이 다른 앱 위에 창을 띄우려면 `SYSTEM_ALERT_WINDOW`("다른 앱 위에 표시") 권한을
 * 따로 받아야 한다. 접근성 서비스는 `TYPE_ACCESSIBILITY_OVERLAY` 창을 쓸 수 있고, 이때는
 * **접근성 서비스 자체가 권한 역할**을 하므로 추가 권한 요청이 없다. 사용자가 이미 켠
 * 권한 하나로 끝난다.
 *
 * ## 컨텍스트를 이렇게 만드는 이유
 *
 * Service 컨텍스트는 디스플레이에 연결된 컨텍스트가 아니다. 예전에 Application 컨텍스트로
 * `Context.getDisplay()` 를 불러 앱이 죽은 적이 있는데, 창 관련 API 는 전부 같은 규칙을
 * 따른다. 그래서 여기서는 `DisplayManager` 로 실제 디스플레이를 얻어
 * `createDisplayContext()` → `createWindowContext()` 로 **창을 띄워도 되는 컨텍스트**를
 * 만들어 쓴다. 짐작하지 않고 규칙대로 만든다.
 *
 * ## 좌표가 틀어지지 않게 하는 방법
 *
 * 오버레이에 `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` 를 주고 인셋에 맞추지 않도록
 * 해서, 창의 (0,0) 이 **디스플레이의 (0,0)** 과 같아지게 한다. 그러면 오버레이가 받은
 * 터치 좌표와 `dispatchGesture` 가 요구하는 화면 좌표가 같은 공간이 되어, 상태바·내비바
 * 높이만큼 어긋나는 문제가 아예 생기지 않는다. 보정값을 더하지 않아도 되는 이유다.
 */
class OverlayController(private val service: AccessibilityService) {

    /** 창을 띄워도 되는 컨텍스트. 만들지 못하면 서비스 컨텍스트로 물러난다. */
    private val windowContext: Context = createWindowContext()

    private val windowManager: WindowManager =
        windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val shown = LinkedHashMap<String, Entry>()

    private class Entry(val view: View, var params: WindowManager.LayoutParams)

    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return service
        return runCatching {
            val displayManager =
                service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching service
            service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        }.onFailure {
            Log.w(TAG, "창 컨텍스트를 만들지 못해 서비스 컨텍스트를 씁니다", it)
        }.getOrDefault(service)
    }

    // ------------------------------------------------------------------
    // 화면 정보
    // ------------------------------------------------------------------

    /**
     * 지금 디스플레이의 크기와 회전.
     *
     * 저장된 영역 좌표가 아직 유효한지 대조하는 기준이 된다.
     */
    @Suppress("DEPRECATION")
    fun screenFingerprint(): ScreenFingerprint {
        // Context.getDisplay() 는 API 30 부터다. 그 아래에서는 부르는 순간 죽으므로
        // 버전을 먼저 보고 갈라 쓴다. 창 정보를 얻는 길이 여러 개라는 이유만으로
        // 아무거나 부르지 않는다.
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowContext.display?.rotation }.getOrNull()
        } else {
            runCatching { windowManager.defaultDisplay?.rotation }.getOrNull()
        } ?: 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
            if (bounds != null) {
                return ScreenFingerprint(bounds.width(), bounds.height(), rotation)
            }
        }
        val metrics = windowContext.resources.displayMetrics
        return ScreenFingerprint(metrics.widthPixels, metrics.heightPixels, rotation)
    }

    // ------------------------------------------------------------------
    // 창 띄우기 / 내리기
    // ------------------------------------------------------------------

    fun isShowing(key: String): Boolean = shown.containsKey(key)

    fun show(key: String, view: View, params: WindowManager.LayoutParams): Boolean {
        remove(key)
        return runCatching {
            windowManager.addView(view, params)
            shown[key] = Entry(view, params)
            true
        }.onFailure { Log.e(TAG, "오버레이($key)를 띄우지 못했습니다", it) }
            .getOrDefault(false)
    }

    fun remove(key: String) {
        val entry = shown.remove(key) ?: return
        runCatching { windowManager.removeViewImmediate(entry.view) }
            .onFailure { Log.w(TAG, "오버레이($key)를 내리지 못했습니다", it) }
    }

    fun removeAll() {
        shown.keys.toList().forEach { remove(it) }
    }

    fun update(key: String, transform: (WindowManager.LayoutParams) -> Unit): Boolean {
        val entry = shown[key] ?: return false
        transform(entry.params)
        return runCatching {
            windowManager.updateViewLayout(entry.view, entry.params)
            true
        }.onFailure { Log.w(TAG, "오버레이($key)를 고치지 못했습니다", it) }
            .getOrDefault(false)
    }

    /**
     * 오버레이가 터치를 받을지 여부를 바꾼다.
     *
     * **주입 직전에 반드시 꺼야 한다.** `dispatchGesture` 는 해당 좌표의 가장 위에 있는
     * 터치 가능한 창으로 간다. 우리 오버레이가 그 자리에 터치 가능 상태로 있으면 주입이
     * 게임이 아니라 우리에게 되돌아온다.
     */
    fun setTouchable(key: String, touchable: Boolean): Boolean {
        val applied = update(key) { params ->
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }
        Log.i(
            TAG,
            "OVERLAY_TOUCHABLE key=$key → $touchable applied=$applied",
        )
        return applied
    }

    /**
     * 이 오버레이가 지금 터치를 받는 상태인가.
     *
     * 창이 없으면 null. 주입이 끝났는데 false 로 남아 있으면 입력이 영영 막힌 것이므로,
     * 바깥에서 이 값을 확인해 되돌릴 수 있어야 한다.
     */
    fun isTouchable(key: String): Boolean? {
        val entry = shown[key] ?: return null
        return entry.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
    }

    fun moveTo(key: String, region: Region): Boolean = update(key) { params ->
        params.x = region.left
        params.y = region.top
        params.width = region.width
        params.height = region.height
    }

    // ------------------------------------------------------------------
    // 창 설정 만들기
    // ------------------------------------------------------------------

    /** 화면 전체를 덮는 오버레이. 영역 지정 화면에서만 쓴다. */
    fun fullScreenParams(touchable: Boolean = true): WindowManager.LayoutParams =
        baseParams(touchable).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
        }

    /** 지정된 영역만 덮는 오버레이. MASTER 입력 받이가 이걸 쓴다. */
    fun regionParams(region: Region, touchable: Boolean = true): WindowManager.LayoutParams =
        baseParams(touchable).apply {
            width = region.width
            height = region.height
            x = region.left
            y = region.top
        }

    /** 작은 떠 있는 조작 버튼. 화면을 덮지 않는다. */
    fun floatingParams(x: Int, y: Int): WindowManager.LayoutParams =
        baseParams(touchable = true).apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            this.x = x
            this.y = y
        }

    private fun baseParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            this.flags = flags
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            // 인셋에 맞춰 창을 밀지 않게 한다. 이게 있어야 창 (0,0) 과 화면 (0,0) 이 같다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }

    private companion object {
        const val TAG = "OverlayController"
    }
}
