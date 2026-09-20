package com.macromobile.imagemacro.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.roundToInt

/** 템플릿 이미지 한 개(원본 + 알파 마스크). */
class LoadedTemplate(val image: Mat, val mask: Mat?) {
    val width: Int get() = image.cols()
    val height: Int get() = image.rows()

    fun release() {
        image.release()
        mask?.release()
    }
}

/**
 * 템플릿 PNG 를 디코딩해 Mat 으로 들고 있는 캐시.
 *
 * - 같은 이미지를 반복해서 파일에서 읽지 않는다.
 * - 해상도 보정을 위해 배율별로 리사이즈한 결과도 함께 캐시한다.
 *
 * 저장된 매크로/템플릿이 바뀌면 [invalidate] 로 비워야 한다.
 */
class TemplateCache(private val maxScaledEntries: Int = 96) {

    private val raw = HashMap<String, LoadedTemplate>()
    private val scaled = LinkedHashMap<String, LoadedTemplate>()

    /**
     * 파일에서 템플릿을 읽는다. 알파 채널이 있으면 마스크로 분리한다.
     *
     * @return 읽지 못하면 null
     */
    @Synchronized
    fun load(file: File): LoadedTemplate? {
        val key = file.absolutePath + ":" + file.lastModified()
        raw[key]?.let { return it }

        val bitmap: Bitmap = try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "템플릿 디코딩 중 메모리 부족: ${file.name}", e)
            null
        } ?: return null

        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()

        val channels = ArrayList<Mat>()
        Core.split(rgba, channels)

        val image = Mat()
        Imgproc.cvtColor(rgba, image, Imgproc.COLOR_RGBA2RGB)

        // 알파가 실제로 투명 영역을 담고 있을 때만 마스크로 쓴다.
        var mask: Mat? = null
        if (channels.size == 4) {
            val alpha = channels[3]
            val minMax = Core.minMaxLoc(alpha)
            if (minMax.minVal < 250.0) {
                val m = Mat()
                val three = ArrayList<Mat>(listOf(alpha, alpha, alpha))
                Core.merge(three, m)
                mask = m
            }
        }
        channels.forEach { if (it !== mask) it.release() }
        rgba.release()

        val loaded = LoadedTemplate(image, mask)
        raw[key] = loaded
        return loaded
    }

    /**
     * [scale] 배율로 리사이즈한 템플릿.
     *
     * 배율이 1 에 가까우면 원본을 그대로 돌려준다.
     */
    @Synchronized
    fun scaled(file: File, scale: Float): LoadedTemplate? {
        val base = load(file) ?: return null
        if (scale in 0.999f..1.001f) return base

        val key = "${file.absolutePath}:${file.lastModified()}:${(scale * 1000).roundToInt()}"
        scaled[key]?.let {
            // LRU: 최근 사용을 뒤로 옮긴다.
            scaled.remove(key)
            scaled[key] = it
            return it
        }

        val w = max(1, (base.width * scale).roundToInt())
        val h = max(1, (base.height * scale).roundToInt())
        val interp = if (scale < 1f) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        val img = Mat()
        Imgproc.resize(base.image, img, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interp)
        val msk = base.mask?.let {
            val m = Mat()
            Imgproc.resize(it, m, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            m
        }
        val entry = LoadedTemplate(img, msk)
        scaled[key] = entry
        trimScaled()
        return entry
    }

    private fun trimScaled() {
        while (scaled.size > maxScaledEntries) {
            val oldest = scaled.keys.firstOrNull() ?: break
            scaled.remove(oldest)?.release()
        }
    }

    @Synchronized
    fun invalidate() {
        raw.values.forEach { it.release() }
        raw.clear()
        scaled.values.forEach { it.release() }
        scaled.clear()
    }

    private companion object {
        const val TAG = "TemplateCache"
    }
}
