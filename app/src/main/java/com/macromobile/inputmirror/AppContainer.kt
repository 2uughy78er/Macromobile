package com.macromobile.inputmirror

import android.content.Context
import com.macromobile.inputmirror.storage.MirrorLogStore
import com.macromobile.inputmirror.storage.SettingsRepository

/** 앱 전역에서 하나만 두고 쓰는 객체들. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val settingsRepository = SettingsRepository(appContext)
    val logStore = MirrorLogStore(appContext)
}
