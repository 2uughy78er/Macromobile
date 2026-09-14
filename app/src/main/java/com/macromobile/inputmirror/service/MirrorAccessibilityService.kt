package com.macromobile.inputmirror.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import com.macromobile.inputmirror.model.MirrorRegion
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.model.ScreenFingerprint
import com.macromobile.inputmirror.MirrorApp
import com.macromobile.inputmirror.mirror.MirrorEngine
import com.macromobile.inputmirror.overlay.OverlayController
import com.macromobile.inputmirror.overlay.RegionEditorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 제스처 전송 결과.
 *
 * "실패"를 한 덩어리로 뭉뚱그리지 않는다. 원인이 다르면 고치는 방법도 다르기 때문이다.
 * - [Rejected]: `dispatchGesture` 가 false 를 돌려줬다. 아예 접수되지 않았다.
 * - [Cancelled]: 접수는 됐는데 시스템이 도중에 취소했다.
 * - [Threw]: 제스처를 만드는 중 예외가 났다. 메시지를 그대로 보존한다.
 */
sealed interface DispatchResult {
    data object Success : DispatchResult
    data class Rejected(val reason: String) : DispatchResult
    data class Cancelled(val reason: String) : DispatchResult
    data class Threw(val reason: String) : DispatchResult

    /**
     * 접수는 됐는데 콜백이 끝내 오지 않았다.
     *
     * `dispatchGesture` 가 true 를 돌려줬는데 onCompleted/onCancelled 중 어느 것도
     * 오지 않는 경우가 실제로 있다. 기다리는 쪽이 영원히 매달리면 그 뒤의 모든 입력이
     * 멈추므로, 일정 시간이 지나면 이 결과로 끊는다. **성공으로 치지 않는다.**
     */
    data class TimedOut(val reason: String) : DispatchResult

    /** 사용자에게 보여줄 사유. 성공이면 빈 문자열. */
    val message: String
        get() = when (this) {
            Success -> ""
            is Rejected -> reason
            is Cancelled -> reason
            is Threw -> reason
            is TimedOut -> reason
        }

    val isSuccess: Boolean get() = this is Success
}

/**
 * 대상 영역에 터치를 실제로 주입하는 서비스.
 *
 * 루팅 없이 다른 창에 입력을 넣을 수 있는 유일한 공식 경로다. 다만 `dispatchGesture` 는
 * **화면 좌표**에 주입하는 것이지 앱을 지정해 보내는 것이 아니다. 그래서 대상 창이
 * 화면에 보이지 않으면 입력이 갈 곳 자체가 없다. 이 앱이 분할화면을 전제로 하는 이유다.
 */
class MirrorAccessibilityService : AccessibilityService() {

    /**
     * 오버레이 창 관리자.
     *
     * 서비스가 실제로 연결된 뒤에만 만든다. 그 전에는 창을 띄울 수 있는 컨텍스트가 없다.
     */
    var overlay: OverlayController? = null
        private set

    /**
     * 미러링 엔진.
     *
     * Activity 가 아니라 여기 산다. 사용자가 게임을 하는 동안 우리 화면은 떠 있지 않기
     * 때문이다. Activity 에 매어두면 그때 미러링이 멈춘다.
     */
    var engine: MirrorEngine? = null
        private set

    /** 서비스가 사는 동안만 유지되는 코루틴 범위. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        val container = MirrorApp.container()
        engine = MirrorEngine(
            service = this,
            scope = serviceScope,
            logStore = container.logStore,
            layoutRepository = container.layoutRepository,
            settingsRepository = container.settingsRepository,
        )
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        screenFlow.value = overlay?.screenFingerprint() ?: ScreenFingerprint()
        instanceFlow.value = this
        Log.i(TAG, "접근성 서비스 연결됨 · 화면 ${screenFlow.value.describe()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이 앱은 화면 이벤트를 쓰지 않는다. 입력 주입에만 접근성 서비스를 사용한다.
    }

    override fun onInterrupt() {
        Log.w(TAG, "접근성 서비스 인터럽트")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        engine?.stop()
        engine = null
        overlay?.removeAll()
        overlay = null
        instanceFlow.value = null
        Log.i(TAG, "접근성 서비스 연결 해제")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        overlay?.removeAll()
        overlay = null
        instanceFlow.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전하거나 멀티윈도우 크기가 바뀌면 저장된 좌표는 더 이상 같은 곳을 가리키지
        // 않는다. 여기서 조용히 고치지 않고, 바뀐 사실만 알린다. 어떻게 할지는
        // 배치를 들고 있는 쪽이 사용자에게 물어 정한다.
        val next = overlay?.screenFingerprint() ?: ScreenFingerprint()
        val changed = !screenFlow.value.matches(next)
        screenFlow.value = next
        Log.i(TAG, "화면 구성 변경: ${next.describe()}")
        // 좌표가 더 이상 같은 곳을 가리키지 않는다. 조용히 계속하지 않고 멈춘다.
        if (changed) engine?.onScreenChanged()
    }

    // ------------------------------------------------------------------
    // 영역 지정 오버레이
    // ------------------------------------------------------------------

    /**
     * 화면 위에 영역 지정 오버레이를 띄운다.
     *
     * 게임 위에 그대로 덮이므로 사용자가 실제 화면을 보면서 영역을 그릴 수 있다.
     */
    fun showRegionEditor(
        editingName: String,
        existing: List<MirrorRegion>,
        initial: Region?,
        onConfirm: (Region) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        val controller = overlay ?: return false
        val view: View = RegionEditorView(
            context = this,
            editingName = editingName,
            existing = existing,
            candidates = WindowInspector.candidates(this),
            initial = initial,
            onConfirm = { region ->
                hideRegionEditor()
                onConfirm(region)
            },
            onCancel = {
                hideRegionEditor()
                onCancel()
            },
        )
        return controller.show(KEY_REGION_EDITOR, view, controller.fullScreenParams())
    }

    fun hideRegionEditor() {
        overlay?.remove(KEY_REGION_EDITOR)
    }

    /** 지금 화면의 창 목록을 사람이 읽을 수 있게. 영역 지정을 도우려고 보여준다. */
    fun windowDump(): String = WindowInspector.dump(this)

    /** 지금 화면 조건. 저장된 좌표가 아직 유효한지 대조하는 데 쓴다. */
    fun screenFingerprint(): ScreenFingerprint =
        overlay?.screenFingerprint() ?: ScreenFingerprint()

    /**
     * 제스처를 보내고 끝날 때까지 기다린다.
     *
     * 이어붙이는 스트로크(`continueStroke`)는 앞 제스처가 끝난 뒤에만 보낼 수 있으므로
     * 호출한 쪽이 순서를 지킬 수 있도록 완료를 기다리는 형태로 둔다.
     */
    suspend fun dispatch(
        gesture: GestureDescription,
        /** `dispatchGesture` 의 반환값을 그대로 알려준다. 접수 여부와 취소를 구분하기 위함이다. */
        onAccepted: (Boolean) -> Unit = {},
    ): DispatchResult = suspendCancellableCoroutine { cont ->
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "GESTURE_CALLBACK onCompleted")
                if (cont.isActive) cont.resume(DispatchResult.Success)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "GESTURE_CALLBACK onCancelled")
                if (cont.isActive) {
                    cont.resume(
                        DispatchResult.Cancelled(
                            "시스템이 제스처를 취소했습니다. 주입 좌표가 이 창 밖이거나, " +
                                "다른 제스처가 끼어들었거나, 대상 창이 입력을 막고 있을 수 있습니다.",
                        ),
                    )
                }
            }
        }
        val accepted = try {
            dispatchGesture(gesture, callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "제스처 전송 중 예외", e)
            onAccepted(false)
            if (cont.isActive) {
                cont.resume(DispatchResult.Threw(e.message ?: e::class.java.simpleName))
            }
            return@suspendCancellableCoroutine
        }
        Log.i(TAG, "DISPATCH_RESULT accepted=$accepted")
        onAccepted(accepted)
        if (!accepted && cont.isActive) {
            cont.resume(
                DispatchResult.Rejected(
                    "dispatchGesture 가 false 를 돌려줬습니다. 접수 자체가 되지 않았습니다.",
                ),
            )
        }
    }

    companion object {
        private const val TAG = "MirrorA11yService"

        const val KEY_REGION_EDITOR = "region_editor"

        private val instanceFlow = MutableStateFlow<MirrorAccessibilityService?>(null)

        /** 화면 구성이 바뀔 때마다 갱신된다. 배치 유효성 검사가 이 값을 본다. */
        private val screenFlow = MutableStateFlow(ScreenFingerprint())
        val screen: StateFlow<ScreenFingerprint> = screenFlow.asStateFlow()

        val instance: MirrorAccessibilityService? get() = instanceFlow.value
        val connected: StateFlow<MirrorAccessibilityService?> = instanceFlow.asStateFlow()
        val isConnected: Boolean get() = instanceFlow.value != null

        /** 한 제스처에 넣을 수 있는 스트로크(손가락) 개수. 대상 개수의 상한이 된다. */
        val maxStrokeCount: Int get() = GestureDescription.getMaxStrokeCount()

        /** 한 제스처의 최대 길이(ms). 긴 드래그를 나눠 보내야 하는 기준이 된다. */
        val maxGestureDurationMs: Long get() = GestureDescription.getMaxGestureDuration()

        /** 시스템 설정에서 이 서비스가 켜져 있는지. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${MirrorAccessibilityService::class.java.name}"
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            } catch (e: Exception) {
                Log.w(TAG, "접근성 설정을 읽지 못했습니다", e)
                null
            } ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
