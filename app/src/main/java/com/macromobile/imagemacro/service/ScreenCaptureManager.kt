package com.macromobile.imagemacro.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.macromobile.imagemacro.vision.ScreenFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 화면 캡처 실패 사유. 사용자에게 그대로 보여줄 수 있는 문장을 담는다. */
sealed interface CaptureResult {
    data class Ok(val frame: ScreenFrame) : CaptureResult

    /**
     * @param fatal 매크로를 더 진행할 수 없는 상태인지.
     *   `true` 면 권한 만료·캡처 중지처럼 다시 시도해도 소용없는 경우라 매크로를 멈춘다.
     *   `false` 면 아직 첫 프레임이 안 나온 것처럼 잠시 뒤 다시 시도하면 되는 경우다.
     */
    data class Error(val message: String, val fatal: Boolean = true) : CaptureResult
}

/**
 * MediaProjection 으로 화면을 캡처한다.
 *
 * - VirtualDisplay + ImageReader 를 한 번만 만들어두고 최신 프레임을 재사용한다.
 *   (매번 새로 만들면 화면이 깜빡이고 느리다)
 * - 캡처한 이미지는 기기 안에서만 쓰이며 어디에도 전송하지 않는다.
 *
 * Android 10 이상에서는 반드시 `mediaProjection` 타입 foreground service 가 먼저
 * 시작되어 있어야 하므로, 이 클래스는 [MacroForegroundService] 안에서만 생성한다.
 */
class ScreenCaptureManager(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * ImageReader 에서 프레임을 꺼내는 구간을 한 번에 하나만 실행하도록 막는다.
     *
     * 매크로 엔진과 타겟 감시가 동시에 화면을 요청할 수 있는데, `acquireLatestImage` 를
     * 겹쳐서 부르면 버퍼 개수를 넘겨 예외가 나거나 엉뚱한 프레임을 가져간다.
     */
    private val acquireLock = Mutex()

    /** 마지막으로 캡처한 프레임. 짧은 시간 안의 재요청은 이 값을 재사용한다. */
    @Volatile
    private var lastFrame: ScreenFrame? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 이 중지되었습니다")
            stopCapture()
        }
    }

    val screenWidth: Int get() = captureWidth
    val screenHeight: Int get() = captureHeight

    /**
     * 캡처를 시작한다.
     *
     * @return 실패 사유. 성공하면 null.
     */
    @Synchronized
    fun startCapture(mediaProjection: MediaProjection): String? {
        stopCapture()
        return try {
            val metrics = currentMetrics()
            captureWidth = metrics.widthPixels
            captureHeight = metrics.heightPixels
            captureDensity = metrics.densityDpi
            if (captureWidth <= 0 || captureHeight <= 0) {
                return "화면 크기를 알 수 없습니다."
            }

            val thread = HandlerThread("ScreenCapture").also { it.start() }
            handlerThread = thread
            handler = Handler(thread.looper)

            val reader = ImageReader.newInstance(
                captureWidth, captureHeight, PixelFormat.RGBA_8888, IMAGE_BUFFER_SIZE,
            )
            imageReader = reader

            // Android 14+ 는 콜백 등록이 필수다.
            mediaProjection.registerCallback(projectionCallback, handler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ImageMacroCapture",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
            if (virtualDisplay == null) {
                stopCapture()
                return "가상 디스플레이를 만들지 못했습니다."
            }
            projection = mediaProjection
            _active.value = true
            null
        } catch (e: SecurityException) {
            stopCapture()
            "화면 캡처 권한이 만료되었습니다. 다시 허용해주세요."
        } catch (e: Exception) {
            Log.e(TAG, "화면 캡처 시작 실패", e)
            stopCapture()
            "화면 캡처를 시작하지 못했습니다: ${e.message ?: "알 수 없는 오류"}"
        }
    }

    /**
     * 매크로 엔진이 쓰는 공용 프레임을 가져온다.
     *
     * 돌려준 프레임은 **다음 [captureFrame] 호출 때 해제**되므로 오래 들고 있으면 안 된다.
     * 이 슬롯은 순차적으로 도는 매크로 엔진 전용이다. 엔진과 동시에 도는 코드
     * (타겟 감시, 이미지 등록 화면)는 반드시 [captureStandalone] 을 써야 한다.
     *
     * @param maxAgeMs 이 시간 안에 캡처한 프레임이 있으면 재사용한다(0 이면 항상 새로 캡처).
     */
    suspend fun captureFrame(maxAgeMs: Long = 0L): CaptureResult {
        if (!_active.value) {
            return CaptureResult.Error("화면 캡처가 시작되지 않았습니다. 화면 캡처 권한을 허용해주세요.")
        }
        lastFrame?.let { cached ->
            if (maxAgeMs > 0 && System.currentTimeMillis() - cached.timestampMs <= maxAgeMs) {
                return CaptureResult.Ok(cached)
            }
        }

        // VirtualDisplay 가 첫 프레임을 그릴 때까지 잠깐 기다려야 하는 경우가 있다.
        repeat(ACQUIRE_RETRIES) { attempt ->
            when (val r = acquireLock.withLock { acquireOnce(track = true) }) {
                is CaptureResult.Ok -> return r
                is CaptureResult.Error -> if (attempt == ACQUIRE_RETRIES - 1) return r
            }
            delay(ACQUIRE_RETRY_DELAY_MS)
        }
        return CaptureResult.Error("화면을 캡처하지 못했습니다.", fatal = false)
    }

    /**
     * 호출한 쪽이 소유하는 독립된 프레임을 캡처한다.
     *
     * [captureFrame] 의 공용 슬롯을 건드리지 않으므로, 매크로 엔진과 **동시에** 도는
     * 코드(타겟 감시)나 화면을 오래 들고 있어야 하는 곳(이미지 등록 화면)에서 쓴다.
     * 다 쓴 뒤에는 호출한 쪽이 `release()` 또는 `releaseAll()` 로 정리해야 한다.
     */
    suspend fun captureStandalone(): CaptureResult {
        if (!_active.value) {
            return CaptureResult.Error("화면 캡처가 시작되지 않았습니다. 화면 캡처 권한을 허용해주세요.")
        }
        repeat(ACQUIRE_RETRIES) { attempt ->
            when (val r = acquireLock.withLock { acquireOnce(track = false) }) {
                is CaptureResult.Ok -> return r
                is CaptureResult.Error -> if (attempt == ACQUIRE_RETRIES - 1) return r
            }
            delay(ACQUIRE_RETRY_DELAY_MS)
        }
        return CaptureResult.Error("화면을 캡처하지 못했습니다.", fatal = false)
    }

    /** 마지막으로 캡처한 프레임(새로 캡처하지 않음). */
    fun getLatestFrame(): ScreenFrame? = lastFrame

    private fun acquireOnce(track: Boolean): CaptureResult {
        val reader = imageReader
            ?: return CaptureResult.Error("화면 캡처가 준비되지 않았습니다.")


        var image: Image? = null
        try {
            // acquireLatestImage 는 큐에 쌓인 오래된 프레임을 알아서 버리고 최신 것만 준다.
            image = reader.acquireLatestImage()
            val img = image ?: return reuseLastFrame(track)

            val bitmap = toBitmap(img)
                ?: return CaptureResult.Error("화면 프레임을 이미지로 변환하지 못했습니다.", fatal = false)

            val frame = ScreenFrame(bitmap)
            if (track) swapLastFrame(frame)
            return CaptureResult.Ok(frame)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "화면 캡처 중 메모리 부족", e)
            return CaptureResult.Error("메모리가 부족합니다. 매칭 해상도를 낮추거나 다른 앱을 종료해주세요.")
        } catch (e: Exception) {
            Log.e(TAG, "화면 캡처 실패", e)
            return CaptureResult.Error(
                "화면 캡처에 실패했습니다: ${e.message ?: "알 수 없는 오류"}",
                fatal = false,
            )
        } finally {
            image?.close()
        }
    }

    /**
     * 새 프레임이 없을 때 직전 프레임을 대신 돌려준다.
     *
     * VirtualDisplay 는 **화면 내용이 바뀔 때만** 새 프레임을 만든다. 로딩 화면이나
     * 대기 화면처럼 그림이 멈춰 있으면 `acquireLatestImage()` 가 계속 null 을 준다.
     * 이때 직전 프레임이 곧 지금 화면이므로 그대로 쓰는 것이 맞다.
     * (이걸 오류로 처리하면 정지 화면을 기다리는 단계에서 매크로가 그냥 멈춰버린다.)
     */
    private fun reuseLastFrame(track: Boolean): CaptureResult {
        val previous = lastFrame
            ?: return CaptureResult.Error(
                "아직 첫 화면 프레임이 준비되지 않았습니다. 잠시 후 다시 시도합니다.",
                fatal = false,
            )
        if (track) return CaptureResult.Ok(previous)

        // 독립 프레임을 요청한 쪽은 자기 것을 해제하므로 복사본을 준다.
        val copy = try {
            if (previous.bitmap.isRecycled) null else previous.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "직전 프레임 복사 중 메모리 부족", e)
            null
        } ?: return CaptureResult.Error("화면 프레임을 복사하지 못했습니다.", fatal = false)
        return CaptureResult.Ok(ScreenFrame(copy, previous.timestampMs))
    }

    /**
     * ImageReader 의 Image 를 Bitmap 으로 바꾼다.
     *
     * 버퍼의 한 줄 길이(rowStride)는 화면 너비보다 클 수 있어서 남는 패딩을 잘라내야 한다.
     */
    private fun toBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + (if (pixelStride > 0) rowPadding / pixelStride else 0)

        val padded = Bitmap.createBitmap(
            paddedWidth.coerceAtLeast(image.width),
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        if (padded.width == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun swapLastFrame(frame: ScreenFrame) {
        val previous = lastFrame
        lastFrame = frame
        previous?.releaseAll()
    }

    @Synchronized
    fun stopCapture() {
        _active.value = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        lastFrame?.releaseAll()
        lastFrame = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        handler = null
    }

    @Suppress("DEPRECATION")
    private fun currentMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.configuration.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    /** 화면 회전 등으로 해상도가 바뀌었는지 확인한다. */
    fun screenSizeChanged(): Boolean {
        if (!_active.value) return false
        val m = currentMetrics()
        return m.widthPixels != captureWidth || m.heightPixels != captureHeight
    }

    private companion object {
        const val TAG = "ScreenCaptureManager"
        const val IMAGE_BUFFER_SIZE = 3
        const val ACQUIRE_RETRIES = 12
        const val ACQUIRE_RETRY_DELAY_MS = 80L
    }
}
