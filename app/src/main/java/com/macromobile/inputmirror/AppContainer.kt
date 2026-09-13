package com.macromobile.inputmirror

import android.content.Context
import com.macromobile.inputmirror.storage.LayoutRepository
import com.macromobile.inputmirror.storage.MirrorLogStore
import com.macromobile.inputmirror.storage.SettingsRepository

/** 앱 전역에서 하나만 두고 쓰는 객체들. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val settingsRepository = SettingsRepository(appContext)

    /** 화면 배치(MASTER/TARGET 영역). 앱을 껐다 켜도 남는다. */
    val layoutRepository = LayoutRepository(appContext)
    val logStore = MirrorLogStore(appContext)
}
