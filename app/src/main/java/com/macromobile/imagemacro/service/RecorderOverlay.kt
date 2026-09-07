package com.macromobile.imagemacro.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.macromobile.imagemacro.automation.MacroRecorder
import com.macromobile.imagemacro.automation.RecordedGesture
import com.macromobile.imagemacro.input.GestureController
import com.macromobile.imagemacro.input.GestureOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 사용자의 터치·드래그를 녹화하는 투명 오버레이.
 *
 * 안드로이드는 다른 앱의 터치를 몰래 엿보는 것을 허용하지 않는다. 그래서 화면 전체를
 * 덮는 투명 창이 터치를 **가로채서 기록한 뒤, 접근성 서비스로 아래 앱에 그대로
 * 다시 보내주는** 방식을 쓴다. 루팅 없이 쓸 수 있는 유일한 방법이다.
 *
 * 그래서 생기는 한계가 있고, 숨기지 않고 적어둔다.
 * - 손가락을 뗀 뒤에야 아래 앱에 전달되므로 **반응이 한 박자 늦다.**
 * - 되돌려주는 아주 짧은 순간에는 창이 터치를 받지 않는다. 그 사이의 터치는
 *   아래 앱에 바로 가고 **녹화되지 않는다.** 너무 빠르게 연타하면 몇 개는 놓친다.
 * - 손가락 하나만 기록한다. 두 손가락 확대·축소는 녹화되지 않는다.
 * - 끄는 경로는 시작점과 끝점만 남는다. 곡선으로 끌어도 직선으로 재생된다.
 */
class RecorderOverlay(
    private val context: Context,
    private val gestures: GestureController,
    private val scope: CoroutineScope,
) {
    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 동작 하나가 끝날 때마다 호출된다(화면 픽셀 좌표). */
    var onGesture: ((RecordedGesture) -> Unit)? = null

    /** 되돌려주기에 실패했을 때. 사용자에게 그대로 보여줄 수 있는 문장이 온다. */
    var onError: ((String) -> Unit)? = null

    private var downX = 0
    private var downY = 0
    private var downAt = 0L
    private var maxDistance = 0f
    private var tracking = false

    /** 되돌려주는 중에는 창이 터치를 받지 않는다. */
    @Volatile
    private var replaying = false

    val isActive: Boolean get() = view != null

    private val density: Float get() = context.resources.displayMetrics.density

    /**
     * 녹화용 오버레이를 띄운다.
     *
     * @return 실패 사유. 성공하면 null.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun start(): String? {
        if (isActive) return null
        if (!Settings.canDrawOverlays(context)) {
            return "'다른 앱 위에 표시' 권한이 필요합니다. 권한 설정에서 허용해주세요."
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return "화면 정보를 가져오지 못했습니다."

        // 녹화 중임을 알리는 빨간 테두리. 가운데는 비어 있어 아래 화면이 그대로 보인다.
        val capture = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((3 * density).roundToInt(), Color.argb(200, 235, 70, 70))
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        capture.setOnTouchListener { _, event -> handleTouch(event) }

        return try {
            wm.addView(capture, lp)
            windowManager = wm
            view = capture
            params = lp
            Log.i(TAG, "동작 녹화 시작")
            null
        } catch (e: Exception) {
            Log.e(TAG, "녹화 오버레이를 띄우지 못했습니다", e)
            "녹화 화면을 띄우지 못했습니다: ${e.message ?: "알 수 없는 오류"}"
        }
    }

    fun stop() {
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
        params = null
        windowManager = null
        tracking = false
        replaying = false
        Log.i(TAG, "동작 녹화 중지")
    }

    // ------------------------------------------------------------------
    // 터치 기록
    // ------------------------------------------------------------------

    private fun handleTouch(event: MotionEvent): Boolean {
        if (replaying) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX.roundToInt()
                downY = event.rawY.roundToInt()
                downAt = System.currentTimeMillis()
                maxDistance = 0f
                tracking = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                maxDistance = max(
                    maxDistance,
                    MacroRecorder.distance(
                        downX, downY, event.rawX.roundToInt(), event.rawY.roundToInt(),
                    ),
                )
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                tracking = false
                val gesture = RecordedGesture(
                    startX = downX,
                    startY = downY,
                    endX = event.rawX.roundToInt(),
                    endY = event.rawY.roundToInt(),
                    startedAt = downAt,
                    endedAt = System.currentTimeMillis(),
                    maxDistancePx = maxDistance,
                )
                onGesture?.invoke(gesture)
                replay(gesture)
            }

            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        // true 를 돌려줘야 이어지는 MOVE/UP 이 계속 들어온다.
        return true
    }

    /**
     * 방금 기록한 동작을 아래 앱에 그대로 다시 보낸다.
     *
     * 창을 잠깐 "터치를 받지 않는" 상태로 바꿔야 우리가 보낸 터치가 우리에게 되돌아오지
     * 않고 아래 앱까지 내려간다.
     */
    private fun replay(gesture: RecordedGesture) {
        replaying = true
        scope.launch {
            try {
                setTouchable(false)
                // 창 설정이 실제로 반영될 틈을 준다. 바로 보내면 우리 창이 먹어버릴 수 있다.
                delay(SWITCH_SETTLE_MS)

                val outcome = if (MacroRecorder.isTap(gesture, density)) {
                    gestures.tap(
                        gesture.startX,
                        gesture.startY,
                        holdMs = gesture.durationMs.coerceIn(1L, 10_000L),
                    )
                } else {
                    gestures.swipe(
                        gesture.startX,
                        gesture.startY,
                        gesture.endX,
                        gesture.endY,
                        durationMs = gesture.durationMs.coerceIn(1L, 60_000L),
                    )
                }
                if (outcome is GestureOutcome.Failed) onError?.invoke(outcome.reason)
            } catch (e: Exception) {
                Log.e(TAG, "동작을 되돌려주지 못했습니다", e)
                onError?.invoke("동작을 아래 앱에 전달하지 못했습니다.")
            } finally {
                setTouchable(true)
                replaying = false
            }
        }
    }

    /** 창이 터치를 받을지 말지 바꾼다. WindowManager 는 메인 스레드에서만 만진다. */
    private suspend fun setTouchable(touchable: Boolean) = withContext(Dispatchers.Main) {
        val v = view ?: return@withContext
        val lp = params ?: return@withContext
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(v, lp) }
        Unit
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private companion object {
        const val TAG = "RecorderOverlay"

        /** 창을 터치 통과 상태로 바꾼 뒤 실제로 반영되기까지 기다리는 시간. */
        const val SWITCH_SETTLE_MS = 40L
    }
}
