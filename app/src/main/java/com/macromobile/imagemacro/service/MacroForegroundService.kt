package com.macromobile.imagemacro.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.macromobile.imagemacro.MacroApp
import com.macromobile.imagemacro.R
import com.macromobile.imagemacro.automation.MacroEngine
import com.macromobile.imagemacro.automation.MacroStatus
import com.macromobile.imagemacro.automation.RunState
import com.macromobile.imagemacro.automation.StepExecutor
import com.macromobile.imagemacro.automation.TargetMonitor
import com.macromobile.imagemacro.storage.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 매크로를 실제로 돌리는 Foreground Service.
 *
 * 앱 UI 가 닫혀도 매크로가 계속 돌아가야 하므로 실행 상태는 전부 여기(그리고 [MacroEngine])
 * 에 있고, UI 는 [MacroController] 를 통해 상태를 관찰하기만 한다.
 *
 * MediaProjection 은 Android 10 이상에서 `mediaProjection` 타입 foreground service 가
 * 먼저 떠 있어야 시작할 수 있으므로, 서비스가 startForeground 를 마친 뒤에 캡처를 연다.
 */
class MacroForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var capture: ScreenCaptureManager
    private lateinit var engine: MacroEngine
    private lateinit var overlay: OverlayController

    override fun onCreate() {
        super.onCreate()
        val container = MacroApp.container()
        capture = ScreenCaptureManager(this)

        val executor = StepExecutor(
            capture = capture,
            detector = container.detector,
            gestures = container.gestures,
            textInput = container.textInput,
            keys = container.keyEvents,
            ocr = container.ocr,
            files = container.files,
        )
        engine = MacroEngine(
            scope = serviceScope,
            capture = capture,
            executor = executor,
            monitor = TargetMonitor(capture, container.detector, container.files),
            gestures = container.gestures,
            files = container.files,
        )

        overlay = OverlayController(this).apply {
            onPlayPause = {
                if (engine.status.value.state == RunState.RUNNING) engine.pause() else engine.resume()
            }
            onStop = { engine.stop() }
            onOpenApp = { openApp() }
        }

        MacroController.attach(engine, capture)

        // 상태가 바뀔 때마다 알림과 오버레이를 갱신한다.
        serviceScope.launch {
            val settings = MacroApp.container().settingsRepository.settings
            engine.status.collect { status ->
                updateNotification(status)
                withContext(Dispatchers.Main) {
                    syncOverlay(status, settings.first().showOverlayController)
                }
            }
        }
    }

    /** 실행 중일 때만 오버레이 컨트롤러를 띄운다. */
    private fun syncOverlay(status: MacroStatus, enabled: Boolean) {
        val shouldShow = enabled &&
            (status.state.isActive || status.state == RunState.TARGET_FOUND)
        if (shouldShow) {
            overlay.show()
            overlay.update(status)
        } else {
            overlay.hide()
        }
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { startActivity(intent) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 어떤 경로로 들어와도 먼저 foreground 로 올라가야 한다(ANR/강제 종료 방지).
        startForegroundSafely()

        when (intent?.action) {
            ACTION_START_CAPTURE -> handleStartCapture(intent)
            ACTION_STOP_MACRO -> engine.stop()
            ACTION_PAUSE_MACRO -> engine.pause()
            ACTION_RESUME_MACRO -> engine.resume()
            ACTION_SHUTDOWN -> {
                engine.stop("서비스를 종료합니다.")
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        // 시스템이 서비스를 죽여도 다시 살려 상태를 복구할 수 있게 한다.
        return START_STICKY
    }

    private fun handleStartCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            MacroController.reportError("화면 캡처 권한 정보를 받지 못했습니다. 다시 시도해주세요.")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            MacroController.reportError("이 기기에서는 화면 캡처를 사용할 수 없습니다.")
            return
        }
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 생성 실패", e)
            null
        }
        if (projection == null) {
            MacroController.reportError("화면 캡처를 시작하지 못했습니다. 권한을 다시 허용해주세요.")
            return
        }
        val error = capture.startCapture(projection)
        if (error != null) {
            MacroController.reportError(error)
        } else {
            MacroController.reportError(null)
            Log.i(TAG, "화면 캡처 시작됨 (${capture.screenWidth}x${capture.screenHeight})")
        }
    }

    /**
     * foreground 로 올라간다.
     *
     * Android 10+ 에서 MediaProjection 을 열려면 `mediaProjection` 타입으로 먼저
     * foreground 가 되어 있어야 한다. 화면 캡처 동의 없이 이 타입으로 올라가려 하면
     * Android 14+ 에서 거부되므로, 실패하면 그 사실을 사용자에게 알린다.
     */
    private fun startForegroundSafely() {
        val notification = buildNotification(engineStatusOrIdle())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "foreground 전환 실패", e)
            MacroController.reportError(
                "백그라운드 실행을 시작하지 못했습니다. 알림 권한을 허용했는지 확인해주세요.",
            )
        }
    }

    private fun engineStatusOrIdle(): MacroStatus =
        if (::engine.isInitialized) engine.status.value else MacroStatus()

    private fun updateNotification(status: MacroStatus) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(status)) }
    }

    private fun buildNotification(status: MacroStatus): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when (status.state) {
            RunState.TARGET_FOUND -> "🎯 타겟 발견 - ${status.targetFound?.targetName ?: ""}"
            RunState.RUNNING -> "매크로 실행 중"
            RunState.PAUSED -> "매크로 일시정지"
            RunState.ERROR -> "매크로 오류"
            else -> "매크로 준비됨"
        }
        val text = buildString {
            if (status.macroName.isNotBlank()) append(status.macroName).append(" · ")
            append("단계 ${status.progressLabel}")
            if (status.cycle > 0) append(" · 반복 ${status.cycleLabel}")
        }

        val builder = NotificationCompat.Builder(this, MacroApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_macro)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(status.state.isActive)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (status.state) {
            RunState.RUNNING -> {
                builder.addAction(0, "일시정지", action(ACTION_PAUSE_MACRO))
                builder.addAction(0, "중지", action(ACTION_STOP_MACRO))
            }

            RunState.PAUSED -> {
                builder.addAction(0, "재개", action(ACTION_RESUME_MACRO))
                builder.addAction(0, "중지", action(ACTION_STOP_MACRO))
            }

            else -> builder.addAction(0, "서비스 종료", action(ACTION_SHUTDOWN))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, MacroForegroundService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        overlay.hide()
        engine.stop("서비스가 종료되었습니다.")
        capture.stopCapture()
        MacroController.detach()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MacroFgService"
        private const val NOTIFICATION_ID = 4131

        const val ACTION_START_CAPTURE = "com.macromobile.imagemacro.START_CAPTURE"
        const val ACTION_STOP_MACRO = "com.macromobile.imagemacro.STOP_MACRO"
        const val ACTION_PAUSE_MACRO = "com.macromobile.imagemacro.PAUSE_MACRO"
        const val ACTION_RESUME_MACRO = "com.macromobile.imagemacro.RESUME_MACRO"
        const val ACTION_SHUTDOWN = "com.macromobile.imagemacro.SHUTDOWN"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** 서비스를 띄우고(필요하면) 화면 캡처를 시작한다. */
        fun startCapture(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MacroForegroundService::class.java)
                .setAction(ACTION_START_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /**
         * 이미 떠 있는 서비스에 명령을 보낸다.
         *
         * 이 서비스는 `mediaProjection` 타입 foreground service 이므로 화면 캡처 동의 없이
         * 띄우면 Android 14 이상에서 거부된다. 그래서 서비스는 항상 [startCapture] 로만
         * 시작하고, 이 함수는 살아 있는 서비스를 제어할 때만 쓴다.
         */
        fun send(context: Context, action: String) {
            val intent = Intent(context, MacroForegroundService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}

/**
 * UI 와 서비스를 잇는 다리.
 *
 * 액티비티가 죽었다 살아나도 서비스가 살아 있으면 같은 [MacroEngine] 을 다시 붙잡을 수 있다.
 */
object MacroController {

    private val _engine = MutableStateFlow<MacroEngine?>(null)
    val engine: StateFlow<MacroEngine?> = _engine.asStateFlow()

    private val _capture = MutableStateFlow<ScreenCaptureManager?>(null)
    val capture: StateFlow<ScreenCaptureManager?> = _capture.asStateFlow()

    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

    private val _status = MutableStateFlow(MacroStatus())

    /** 서비스가 없을 때도 안전하게 관찰할 수 있는 상태. */
    val status: StateFlow<MacroStatus> = _status.asStateFlow()

    private var relayScope: CoroutineScope? = null

    internal fun attach(engine: MacroEngine, capture: ScreenCaptureManager) {
        _engine.value = engine
        _capture.value = capture
        relayScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        relayScope = scope
        scope.launch { engine.status.collect { _status.value = it } }
    }

    internal fun detach() {
        relayScope?.cancel()
        relayScope = null
        _engine.value = null
        _capture.value = null
        _status.value = _status.value.copy(state = RunState.IDLE, stepIndex = -1)
    }

    fun reportError(message: String?) {
        _serviceError.value = message
    }

    val isCaptureReady: Boolean get() = _capture.value?.active?.value == true

    /** 화면 캡처가 준비될 때까지 기다린다. */
    suspend fun awaitCapture(): ScreenCaptureManager = _capture.first { it != null }!!

    suspend fun startMacro(
        context: Context,
        macro: com.macromobile.imagemacro.model.Macro,
        settings: AppSettings,
    ): String? {
        val e = _engine.value
            ?: return "매크로 서비스가 아직 준비되지 않았습니다. 잠시 후 다시 시도해주세요."
        return e.start(macro, settings)
    }

    fun pause() = _engine.value?.pause()
    fun resume() = _engine.value?.resume()
    fun stop() = _engine.value?.stop()
    fun clearTargetFound() = _engine.value?.clearTargetFound()
    fun clearError() = _engine.value?.clearError()
}
