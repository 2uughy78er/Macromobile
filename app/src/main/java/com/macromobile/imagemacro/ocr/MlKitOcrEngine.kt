package com.macromobile.imagemacro.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google ML Kit 기반 OCR.
 *
 * 인식 모델은 APK 에 함께 들어가며 전부 기기 안에서 동작한다(네트워크 없음).
 * 특정 앱이나 게임의 폰트 데이터는 포함하지 않는다.
 */
class MlKitOcrEngine : OcrEngine {

    private var latin: TextRecognizer? = null
    private var korean: TextRecognizer? = null

    override suspend fun recognize(bitmap: Bitmap, korean: Boolean): OcrResult {
        val recognizer = try {
            recognizerFor(korean)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 엔진 초기화 실패", e)
            return OcrResult("", 0f)
        }

        return suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (!cont.isActive) return@addOnSuccessListener
                        val blocks = text.textBlocks.map { b ->
                            val box = b.boundingBox
                            OcrBlock(
                                text = b.text,
                                left = box?.left ?: 0,
                                top = box?.top ?: 0,
                                right = box?.right ?: 0,
                                bottom = box?.bottom ?: 0,
                            )
                        }
                        // ML Kit 은 블록별 신뢰도를 공개하지 않는다.
                        // 글자를 읽었는지 여부를 대신 신뢰도로 삼는다.
                        val confidence = if (text.text.isBlank()) 0f else 1f
                        cont.resume(OcrResult(text.text.trim(), confidence, blocks))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR 인식 실패", e)
                        if (cont.isActive) cont.resume(OcrResult("", 0f))
                    }
            } catch (e: Exception) {
                Log.e(TAG, "OCR 처리 중 오류", e)
                if (cont.isActive) cont.resume(OcrResult("", 0f))
            }
        }
    }

    @Synchronized
    private fun recognizerFor(useKorean: Boolean): TextRecognizer {
        return if (useKorean) {
            korean ?: TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                .also { korean = it }
        } else {
            latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { latin = it }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { latin?.close() }
        runCatching { korean?.close() }
        latin = null
        korean = null
    }

    private companion object {
        const val TAG = "MlKitOcrEngine"
    }
}
