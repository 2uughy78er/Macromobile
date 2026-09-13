package com.macromobile.inputmirror.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macromobile.inputmirror.model.MirrorLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.layoutDataStore: DataStore<Preferences> by preferencesDataStore("mirror_layout")

/**
 * 화면 배치를 저장한다.
 *
 * 좌표는 사용자가 직접 지정한 실제 값이므로 앱을 껐다 켜도 남아야 한다. 다만 저장된
 * 좌표는 **만들어질 때의 화면 조건에서만 뜻이 있다.** 그 조건도 함께 저장해 두고,
 * 불러온 뒤 지금 화면과 대조하는 일은 [MirrorLayout.status] 가 한다.
 */
class LayoutRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val layout: Flow<MirrorLayout> = context.layoutDataStore.data.map { prefs ->
        val raw = prefs[KEY_LAYOUT] ?: return@map MirrorLayout()
        runCatching { json.decodeFromString(MirrorLayout.serializer(), raw) }
            .onFailure { Log.w(TAG, "저장된 배치를 읽지 못했습니다. 빈 배치로 시작합니다.", it) }
            .getOrDefault(MirrorLayout())
    }

    suspend fun update(transform: (MirrorLayout) -> MirrorLayout) {
        context.layoutDataStore.edit { prefs ->
            val current = prefs[KEY_LAYOUT]
                ?.let { runCatching { json.decodeFromString(MirrorLayout.serializer(), it) }.getOrNull() }
                ?: MirrorLayout()
            prefs[KEY_LAYOUT] = json.encodeToString(MirrorLayout.serializer(), transform(current))
        }
    }

    suspend fun clear() {
        context.layoutDataStore.edit { it.remove(KEY_LAYOUT) }
    }

    private companion object {
        const val TAG = "LayoutRepository"
        val KEY_LAYOUT = stringPreferencesKey("layout_json")
    }
}
