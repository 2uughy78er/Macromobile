package com.macromobile.inputmirror

import android.app.Application
import com.macromobile.inputmirror.diag.CrashRecorder
import com.macromobile.inputmirror.diag.DiagLog

/** 앱 진입점. */
class MirrorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 무엇보다 먼저 크래시 기록기를 건다. 이 뒤로 일어나는 어떤 크래시든
        // 스택 트레이스가 파일로 남아 다음 실행 때 앱 안에서 볼 수 있다.
        CrashRecorder.install(this)
        DiagLog.init(this)
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
