package com.macromobile.inputmirror

import android.app.Application

/** 앱 진입점. */
class MirrorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        private var instance: MirrorApp? = null

        fun container(): AppContainer = requireNotNull(instance?.container) {
            "MirrorApp 이 아직 생성되지 않았습니다."
        }
    }
}
