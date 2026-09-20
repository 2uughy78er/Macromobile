package com.macromobile.imagemacro.ocr

import android.graphics.Bitmap

/** OCR 인식 결과. */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val blocks: List<OcrBlock> = emptyList(),
)

/** 인식된 텍스트 덩어리 하나(디버그 표시용). */
data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * 화면의 글자를 읽는 엔진.
 *
 * 구현을 갈아끼울 수 있도록 인터페이스로 둔다. 기본 구현은 기기 안에서만 동작하는
 * ML Kit 이며, 사용자가 직접 글자 모양을 학습시키는 방식(PC 버전의 글리프 매칭)도
 * 나중에 이 인터페이스를 구현해 추가할 수 있다.
 *
 * 어떤 구현이든 화면 이미지를 외부 서버로 보내지 않는다.
 */
interface OcrEngine {
    /** 잘라낸 이미지에서 글자를 읽는다. 실패하면 예외 대신 빈 결과를 돌려준다. */
    suspend fun recognize(bitmap: Bitmap, korean: Boolean): OcrResult

    fun close()
}

/**
 * 인식 결과를 매크로가 쓰기 좋게 다듬는다.
 *
 * @param charset 허용할 문자. 비어 있으면 제한하지 않는다.
 * @param expectedLength 0 이 아니면 이 길이와 다를 때 실패로 본다.
 */
fun OcrResult.normalize(charset: String, expectedLength: Int): String? {
    var out = text.filterNot { it.isWhitespace() }
    if (charset.isNotBlank()) {
        val allowed = charset.toSet()
        out = out.filter { it in allowed }
    }
    if (out.isEmpty()) return null
    if (expectedLength > 0 && out.length != expectedLength) return null
    return out
}
