package com.macromobile.imagemacro.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.macromobile.imagemacro.service.MacroAccessibilityService
import kotlinx.coroutines.delay

/** 텍스트 입력 결과. */
sealed interface TextInputOutcome {
    data class Success(val method: String) : TextInputOutcome
    data class Failed(val reason: String) : TextInputOutcome
}

/**
 * 매크로 도중 텍스트를 자동 입력한다.
 *
 * 1순위: `AccessibilityNodeInfo.ACTION_SET_TEXT` — 가장 확실하고 한글·특수문자도 그대로 들어간다.
 * 2순위: 클립보드에 넣고 `ACTION_PASTE` — SET_TEXT 를 막아둔 입력창을 위한 대비책.
 *
 * 두 방법 모두 대상 앱이 입력창을 접근성 노드로 노출해야 동작한다. 노출하지 않는 앱
 * (일부 게임의 자체 렌더링 입력창 등)에서는 실패하며, 그 사실을 사용자에게 그대로 알린다.
 * PC 버전의 `adb shell input text` 같은 우회 경로는 노루팅 환경에 존재하지 않는다.
 */
class TextInputController(private val context: Context) {

    suspend fun input(text: String, clearFirst: Boolean = true): TextInputOutcome {
        val service = MacroAccessibilityService.instance
            ?: return TextInputOutcome.Failed(
                "접근성 서비스가 연결되어 있지 않아 텍스트를 입력할 수 없습니다.",
            )

        val node = findEditableNode(service)
            ?: return TextInputOutcome.Failed(
                "입력창을 찾지 못했습니다. 먼저 입력창을 터치하는 단계를 앞에 넣어주세요. " +
                    "일부 앱은 입력창을 접근성 서비스에 노출하지 않아 자동 입력이 불가능합니다.",
            )

        try {
            if (clearFirst) {
                setNodeText(node, "")
                delay(60)
            }
            if (setNodeText(node, text)) {
                return TextInputOutcome.Success("ACTION_SET_TEXT")
            }
            // SET_TEXT 가 막힌 입력창을 위한 대비책.
            if (pasteViaClipboard(node, text)) {
                return TextInputOutcome.Success("클립보드 붙여넣기")
            }
        } catch (e: Exception) {
            Log.e(TAG, "텍스트 입력 실패", e)
            return TextInputOutcome.Failed("텍스트 입력 중 오류가 발생했습니다: ${e.message ?: "알 수 없음"}")
        } finally {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }

        return TextInputOutcome.Failed(
            "이 입력창은 자동 입력을 허용하지 않습니다. 직접 입력하거나 다른 단계로 대체해주세요.",
        )
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("macro", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /**
     * 지금 입력을 받을 수 있는 노드를 찾는다.
     *
     * 입력 포커스가 잡힌 노드를 먼저 보고, 없으면 현재 창들을 훑어 편집 가능한 노드를 찾는다.
     */
    private fun findEditableNode(service: MacroAccessibilityService): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isEditable) return focused
            @Suppress("DEPRECATION")
            runCatching { focused.recycle() }
        }

        val roots = buildList {
            runCatching { service.windows }.getOrNull()
                ?.mapNotNull { runCatching { it.root }.getOrNull() }
                ?.let { addAll(it) }
            runCatching { service.rootInActiveWindow }.getOrNull()?.let { add(it) }
        }
        for (root in roots) {
            searchEditable(root, 0)?.let { return it }
        }
        return null
    }

    private fun searchEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            searchEditable(child, depth + 1)?.let { return it }
            @Suppress("DEPRECATION")
            runCatching { child.recycle() }
        }
        return null
    }

    private companion object {
        const val TAG = "TextInputController"
        const val MAX_DEPTH = 40
    }
}
