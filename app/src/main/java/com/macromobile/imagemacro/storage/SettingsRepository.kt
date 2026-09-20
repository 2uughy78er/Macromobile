package com.macromobile.imagemacro.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("macro_settings")

/** 앱 전역 설정. 매크로별 설정은 매크로 JSON 안에 들어간다. */
data class AppSettings(
    /** 화면을 다시 검사하는 기본 간격(ms). */
    val pollIntervalMs: Long = 300L,
    /** 매칭 전에 화면을 줄이는 배율. 1.0 = 원본, 작을수록 빠르고 부정확하다. */
    val analysisScale: Float = 1.0f,
    /** 실패 화면을 logs/ 에 저장할지. */
    val saveDebugScreenshots: Boolean = false,
    /** 매칭 결과를 화면에 표시하는 디버그 오버레이. */
    val showMatchDebugOverlay: Boolean = false,
    /** 실행 중 화면 위에 작은 컨트롤러를 띄울지. */
    val showOverlayController: Boolean = true,
    /** 터치 좌표에 ±N 픽셀 흔들림을 준다. */
    val tapJitterPx: Int = 0,
    /** 대기 시간에 ±비율 만큼 흔들림을 준다. */
    val delayJitter: Float = 0f,
    /** 마지막으로 고른 매크로 ID. */
    val lastMacroId: String = "",
    /** 일시정지 중에도 타겟 감시를 계속할지. */
    val monitorWhilePaused: Boolean = false,
    /** 보관할 로그/스크린샷 개수. */
    val logKeepCount: Int = 200,
) {
    companion object {
        val POLL_CHOICES = listOf(100L, 200L, 300L, 500L, 800L, 1_000L)
        val ANALYSIS_SCALE_CHOICES = listOf(1.0f, 0.75f, 0.5f)

        /** CPU/배터리 경고를 띄워야 하는 간격. */
        const val FAST_POLL_WARNING_MS = 200L
    }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            pollIntervalMs = p[KEY_POLL] ?: 300L,
            analysisScale = p[KEY_ANALYSIS_SCALE] ?: 1.0f,
            saveDebugScreenshots = p[KEY_SAVE_DEBUG] ?: false,
            showMatchDebugOverlay = p[KEY_DEBUG_OVERLAY] ?: false,
            showOverlayController = p[KEY_OVERLAY_CONTROLLER] ?: true,
            tapJitterPx = p[KEY_JITTER_PX] ?: 0,
            delayJitter = p[KEY_DELAY_JITTER] ?: 0f,
            lastMacroId = p[KEY_LAST_MACRO] ?: "",
            monitorWhilePaused = p[KEY_MONITOR_PAUSED] ?: false,
            logKeepCount = p[KEY_LOG_KEEP] ?: 200,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val current = AppSettings(
                pollIntervalMs = p[KEY_POLL] ?: 300L,
                analysisScale = p[KEY_ANALYSIS_SCALE] ?: 1.0f,
                saveDebugScreenshots = p[KEY_SAVE_DEBUG] ?: false,
                showMatchDebugOverlay = p[KEY_DEBUG_OVERLAY] ?: false,
                showOverlayController = p[KEY_OVERLAY_CONTROLLER] ?: true,
                tapJitterPx = p[KEY_JITTER_PX] ?: 0,
                delayJitter = p[KEY_DELAY_JITTER] ?: 0f,
                lastMacroId = p[KEY_LAST_MACRO] ?: "",
                monitorWhilePaused = p[KEY_MONITOR_PAUSED] ?: false,
                logKeepCount = p[KEY_LOG_KEEP] ?: 200,
            )
            val next = transform(current)
            p[KEY_POLL] = next.pollIntervalMs.coerceIn(50L, 10_000L)
            p[KEY_ANALYSIS_SCALE] = next.analysisScale.coerceIn(0.25f, 1f)
            p[KEY_SAVE_DEBUG] = next.saveDebugScreenshots
            p[KEY_DEBUG_OVERLAY] = next.showMatchDebugOverlay
            p[KEY_OVERLAY_CONTROLLER] = next.showOverlayController
            p[KEY_JITTER_PX] = next.tapJitterPx.coerceIn(0, 50)
            p[KEY_DELAY_JITTER] = next.delayJitter.coerceIn(0f, 0.5f)
            p[KEY_LAST_MACRO] = next.lastMacroId
            p[KEY_MONITOR_PAUSED] = next.monitorWhilePaused
            p[KEY_LOG_KEEP] = next.logKeepCount.coerceIn(10, 2_000)
        }
    }

    private companion object {
        val KEY_POLL = longPreferencesKey("poll_interval_ms")
        val KEY_ANALYSIS_SCALE = floatPreferencesKey("analysis_scale")
        val KEY_SAVE_DEBUG = booleanPreferencesKey("save_debug_screenshots")
        val KEY_DEBUG_OVERLAY = booleanPreferencesKey("show_match_debug_overlay")
        val KEY_OVERLAY_CONTROLLER = booleanPreferencesKey("show_overlay_controller")
        val KEY_JITTER_PX = intPreferencesKey("tap_jitter_px")
        val KEY_DELAY_JITTER = floatPreferencesKey("delay_jitter")
        val KEY_LAST_MACRO = stringPreferencesKey("last_macro_id")
        val KEY_MONITOR_PAUSED = booleanPreferencesKey("monitor_while_paused")
        val KEY_LOG_KEEP = intPreferencesKey("log_keep_count")
    }
}
