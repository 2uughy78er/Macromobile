package com.macromobile.imagemacro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 화면을 실제로 터치·스와이프하고 텍스트를 입력하는 서비스.
 *
 * ROOT 권한 없이 다른 앱을 조작할 수 있는 유일한 공식 경로다.
 * 사용자가 Android 설정에서 직접 켜야 하며, 꺼져 있으면 매크로는 실행되지 않는다.
 *
 * 이 서비스 자체는 화면 내용을 저장하거나 밖으로 내보내지 않는다.
 */
class MacroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 제스처 전달과 창 내용 조회를 켠다. XML 설정과 같은 내용을 코드에서도 보장한다.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        instanceFlow.value = this
        Log.i(TAG, "접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 매크로는 화면 이미지를 기준으로 동작하므로 이벤트를 구독할 필요가 없다.
        // (텍스트 입력 시에만 노드 트리를 직접 조회한다.)
    }

    override fun onInterrupt() {
        Log.w(TAG, "접근성 서비스 인터럽트")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instanceFlow.value = null
        Log.i(TAG, "접근성 서비스 연결 해제")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanceFlow.value = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MacroA11yService"

        private val instanceFlow = MutableStateFlow<MacroAccessibilityService?>(null)

        /** 서비스가 연결돼 있으면 인스턴스, 아니면 null. */
        val instance: MacroAccessibilityService? get() = instanceFlow.value

        /** UI 가 연결 상태를 관찰할 수 있게 하는 흐름. */
        val connected: StateFlow<MacroAccessibilityService?> = instanceFlow.asStateFlow()

        val isConnected: Boolean get() = instanceFlow.value != null

        /**
         * 시스템 설정에서 이 서비스가 켜져 있는지 확인한다.
         *
         * 서비스 인스턴스가 아직 살아나기 전에도 상태를 알 수 있어 안내 화면에서 유용하다.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${MacroAccessibilityService::class.java.name}"
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
