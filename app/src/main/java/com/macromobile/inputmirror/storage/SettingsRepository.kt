package com.macromobile.inputmirror.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macromobile.inputmirror.model.FitMode
import com.macromobile.inputmirror.model.MirrorMode
import com.macromobile.inputmirror.model.MirrorSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mirrorDataStore: DataStore<Preferences> by preferencesDataStore("mirror_settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<MirrorSettings> = context.mirrorDataStore.data.map { it.toSettings() }

    suspend fun update(transform: (MirrorSettings) -> MirrorSettings) {
        context.mirrorDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[KEY_MODE] = next.mode.name
            prefs[KEY_DELAY] = next.inputDelayMs.coerceIn(0L, 1_000L)
            prefs[KEY_FIT] = next.fitMode.name
            prefs[KEY_TARGETS] = next.enabledTargets.joinToString(",") { it.toString() }
            prefs[KEY_SCALE_X] = next.scaleX.coerceIn(0.1f, 5f)
            prefs[KEY_SCALE_Y] = next.scaleY.coerceIn(0.1f, 5f)
            prefs[KEY_OFFSET_X] = next.offsetX.coerceIn(-5_000f, 5_000f)
            prefs[KEY_OFFSET_Y] = next.offsetY.coerceIn(-5_000f, 5_000f)
            prefs[KEY_SAVE_LOG] = next.saveLogToFile
        }
    }

    private fun Preferences.toSettings() = MirrorSettings(
        mode = runCatching { MirrorMode.valueOf(this[KEY_MODE] ?: "") }
            .getOrDefault(MirrorMode.STREAMING),
        inputDelayMs = this[KEY_DELAY] ?: 0L,
        fitMode = runCatching { FitMode.valueOf(this[KEY_FIT] ?: "") }.getOrDefault(FitMode.FIT),
        enabledTargets = (this[KEY_TARGETS] ?: "true,true,true")
            .split(",").map { it.trim().toBoolean() }
            .let { if (it.size == 3) it else listOf(true, true, true) },
        scaleX = this[KEY_SCALE_X] ?: 1f,
        scaleY = this[KEY_SCALE_Y] ?: 1f,
        offsetX = this[KEY_OFFSET_X] ?: 0f,
        offsetY = this[KEY_OFFSET_Y] ?: 0f,
        saveLogToFile = this[KEY_SAVE_LOG] ?: true,
    )

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_DELAY = longPreferencesKey("input_delay_ms")
        val KEY_FIT = stringPreferencesKey("fit_mode")
        val KEY_TARGETS = stringPreferencesKey("enabled_targets")
        val KEY_SCALE_X = floatPreferencesKey("scale_x")
        val KEY_SCALE_Y = floatPreferencesKey("scale_y")
        val KEY_OFFSET_X = floatPreferencesKey("offset_x")
        val KEY_OFFSET_Y = floatPreferencesKey("offset_y")
        val KEY_SAVE_LOG = booleanPreferencesKey("save_log")
    }
}
