package com.macromobile.imagemacro.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 화면 캡처 한 장.
 *
 * 한 프레임에서 여러 템플릿을 검사하는 경우가 많으므로 Bitmap → Mat 변환 결과를
 * 프레임 안에 캐시해 재사용한다. 축소 배율별 Mat 도 함께 캐시한다.
 *
 * 다 쓴 뒤에는 반드시 [release] 를 호출해야 네이티브 메모리가 반환된다.
 */
class ScreenFrame(
    val bitmap: Bitmap,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    private var fullMat: Mat? = null
    private val scaledMats = HashMap<Int, Mat>()

    /** 원본 크기의 3채널 RGB Mat. */
    @Synchronized
    fun rgb(): Mat {
        fullMat?.let { return it }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        fullMat = rgb
        return rgb
    }

    /**
     * [analysisScale] 만큼 축소한 Mat. 1.0 이면 [rgb] 와 같다.
     *
     * 매칭 속도를 위해 화면을 줄여서 검사할 때 쓴다. 결과 좌표는 호출한 쪽에서
     * 다시 나눠 원래 좌표로 되돌려야 한다.
     */
    @Synchronized
    fun rgbScaled(analysisScale: Float): Mat {
        if (analysisScale >= 0.999f) return rgb()
        val key = (analysisScale * 1000).roundToInt()
        scaledMats[key]?.let { return it }
        val src = rgb()
        val w = max(1, (src.cols() * analysisScale).roundToInt())
        val h = max(1, (src.rows() * analysisScale).roundToInt())
        val dst = Mat()
        Imgproc.resize(src, dst, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        scaledMats[key] = dst
        return dst
    }

    @Synchronized
    fun release() {
        fullMat?.release()
        fullMat = null
        scaledMats.values.forEach { it.release() }
        scaledMats.clear()
    }

    /** Bitmap 까지 반환한다. Bitmap 을 UI 에서 계속 쓰는 경우에는 호출하지 말 것. */
    @Synchronized
    fun releaseAll() {
        release()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
