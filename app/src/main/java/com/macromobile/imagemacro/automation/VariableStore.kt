package com.macromobile.imagemacro.automation

import kotlin.random.Random

/**
 * 매크로 실행 중에 쓰는 변수 저장소.
 *
 * OCR 로 읽은 값이나 사용자가 미리 넣어둔 값을 담고, 텍스트 입력 단계에서 꺼내 쓴다.
 * 매크로를 시작할 때마다 초기화된다.
 */
class VariableStore {

    private val values = LinkedHashMap<String, String>()

    @Synchronized
    fun reset(initial: Map<String, String> = emptyMap()) {
        values.clear()
        values.putAll(initial)
    }

    @Synchronized
    fun put(name: String, value: String) {
        if (name.isNotBlank()) values[name.trim()] = value
    }

    @Synchronized
    fun get(name: String): String? = values[name.trim()]

    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    /**
     * 텍스트 안의 변수 표기를 실제 값으로 바꾼다.
     *
     * - `@변수명` → 저장된 값 (없으면 표기를 그대로 둔다)
     * - `@random` → 소문자 4자 + 숫자 4자
     * - `@random:8` → 소문자·숫자를 섞은 8자
     * - `@randomnum:6` → 숫자 6자
     * - `@@` → 문자 `@`
     *
     * 표기는 문자열 어디에 있어도 되고 여러 번 나와도 된다.
     */
    @Synchronized
    fun resolve(text: String): String {
        if (!text.contains('@')) return text
        return TOKEN.replace(text) { m ->
            val token = m.groupValues[1]
            val arg = m.groupValues[2].toIntOrNull()
            when {
                token == "@" -> "@"
                token.equals("random", ignoreCase = true) ->
                    if (arg != null) randomAlphaNum(arg) else randomLetters(4) + randomDigits(4)
                token.equals("randomnum", ignoreCase = true) -> randomDigits(arg ?: 6)
                token.equals("randomstr", ignoreCase = true) -> randomLetters(arg ?: 6)
                else -> values[token] ?: m.value
            }
        }
    }

    private fun randomLetters(n: Int): String =
        (1..n.coerceIn(1, 64)).map { LETTERS.random(Random) }.joinToString("")

    private fun randomDigits(n: Int): String =
        (1..n.coerceIn(1, 64)).map { DIGITS.random(Random) }.joinToString("")

    private fun randomAlphaNum(n: Int): String =
        (1..n.coerceIn(1, 64)).map { ALPHANUM.random(Random) }.joinToString("")

    private companion object {
        /** `@이름` 또는 `@이름:숫자` 또는 `@@`. */
        val TOKEN = Regex("@(@|[A-Za-z_][A-Za-z0-9_]*)(?::(\\d+))?")
        val LETTERS = ('a'..'z').toList()
        val DIGITS = ('0'..'9').toList()
        val ALPHANUM = LETTERS + DIGITS
    }
}
