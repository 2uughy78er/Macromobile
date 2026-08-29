package com.macromobile.imagemacro

import com.macromobile.imagemacro.automation.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableStoreTest {

    @Test
    fun `저장한 변수를 꺼내 쓴다`() {
        val store = VariableStore()
        store.put("code", "123456")
        assertEquals("123456", store.resolve("@code"))
        assertEquals("코드는 123456 입니다", store.resolve("코드는 @code 입니다"))
    }

    @Test
    fun `없는 변수는 표기를 그대로 둔다`() {
        val store = VariableStore()
        assertEquals("@missing", store.resolve("@missing"))
    }

    @Test
    fun `골뱅이 두 개는 골뱅이 한 개가 된다`() {
        val store = VariableStore()
        assertEquals("a@b.com", store.resolve("a@@b.com"))
    }

    @Test
    fun `random 은 소문자 넷 숫자 넷을 만든다`() {
        val store = VariableStore()
        val out = store.resolve("@random")
        assertEquals(8, out.length)
        assertTrue(out.take(4).all { it.isLowerCase() })
        assertTrue(out.drop(4).all { it.isDigit() })
    }

    @Test
    fun `길이를 지정한 random 을 만든다`() {
        val store = VariableStore()
        assertEquals(12, store.resolve("@random:12").length)
        assertEquals(6, store.resolve("@randomnum:6").length)
        assertTrue(store.resolve("@randomnum:6").all { it.isDigit() })
        assertTrue(store.resolve("@randomstr:5").all { it.isLowerCase() })
    }

    @Test
    fun `한 문장에 변수가 여러 번 나와도 모두 바뀐다`() {
        val store = VariableStore()
        store.put("a", "1")
        store.put("b", "2")
        assertEquals("1-2-1", store.resolve("@a-@b-@a"))
    }

    @Test
    fun `초기화하면 이전 값이 사라진다`() {
        val store = VariableStore()
        store.put("code", "abc")
        store.reset(mapOf("seed" to "xyz"))
        assertNull(store.get("code"))
        assertEquals("xyz", store.get("seed"))
    }

    @Test
    fun `골뱅이가 없으면 원본을 그대로 돌려준다`() {
        val store = VariableStore()
        assertEquals("hello world", store.resolve("hello world"))
    }
}
