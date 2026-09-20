package com.macromobile.imagemacro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * 앱 진입점.
 *
 * OpenCV 네이티브 라이브러리를 여기서 한 번만 초기화한다. 초기화에 실패하면
 * 이미지 매칭을 할 수 없으므로 그 사실을 [openCvReady] 로 알려 UI 에서 안내한다.
 */
class MacroApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)

        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV 초기화 실패", e)
            false
        }
        if (!openCvReady) {
            Log.e(TAG, "OpenCV 를 불러오지 못했습니다. 이미지 매칭을 사용할 수 없습니다.")
        }

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_RUNNING,
            getString(R.string.channel_running_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_running_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MacroApp"
        const val CHANNEL_RUNNING = "macro_running"

        @Volatile
        var openCvReady: Boolean = false
            private set

        private var instance: MacroApp? = null

        fun container(): AppContainer = requireNotNull(instance?.container) {
            "MacroApp 이 아직 생성되지 않았습니다."
        }
    }
}
