package com.macromobile.imagemacro

import com.macromobile.imagemacro.ocr.OcrResult
import com.macromobile.imagemacro.ocr.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrNormalizeTest {

    @Test
    fun `공백을 없앤다`() {
        assertEquals("AB12", OcrResult("A B 1 2", 1f).normalize("", 0))
    }

    @Test
    fun `허용 문자만 남긴다`() {
        val result = OcrResult("A1B2-C3", 1f)
        assertEquals("123", result.normalize("0123456789", 0))
    }

    @Test
    fun `길이가 맞지 않으면 실패로 본다`() {
        val result = OcrResult("12345", 1f)
        assertNull(result.normalize("", 6))
        assertEquals("12345", result.normalize("", 5))
        assertEquals("12345", result.normalize("", 0))
    }

    @Test
    fun `읽은 글자가 없으면 null 이다`() {
        assertNull(OcrResult("   ", 1f).normalize("", 0))
        assertNull(OcrResult("abc", 1f).normalize("0123456789", 0))
    }
}
