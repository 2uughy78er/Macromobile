package com.macromobile.inputmirror.mirror

import com.macromobile.inputmirror.input.GestureType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 방금 끝난 제스처 하나의 요약. 화면이 이걸 보여준다. */
data class GestureSummary(
    val gestureId: Long,
    val type: GestureType,
    val masterFrom: Pair<Float, Float>,
    val masterTo: Pair<Float, Float>,
    val distance: Float,
    val durationMs: Long,
    /** 대상 이름 → "시작 → 끝" 좌표 문자열. */
    val targetPaths: Map<String, String> = emptyMap(),
    /** 대상 이름 → COMPLETED / CANCELLED / REJECTED / ... */
    val targetResults: Map<String, String> = emptyMap(),
) {
    fun describe(): String = buildString {
        appendLine("GESTURE #$gestureId")
        appendLine("TYPE=${type.label}  거리=${"%.1f".format(distance)}px  ${durationMs}ms")
        appendLine(
            "MASTER : (${masterFrom.first.toInt()},${masterFrom.second.toInt()})" +
                " → (${masterTo.first.toInt()},${masterTo.second.toInt()})",
        )
        targetPaths.forEach { (name, path) -> appendLine("$name: $path") }
        if (targetResults.isNotEmpty()) {
            append("RESULT: ")
            append(targetResults.entries.joinToString("  ") { "${it.key}=${it.value}" })
        }
    }.trimEnd()
}

/**
 * 미러링의 현재 상태를 앱 전체가 함께 보는 곳.
 *
 * 미러링은 접근성 서비스 안에서 돌아간다. 사용자가 게임을 하는 동안 우리 Activity 는
 * 화면에 없기 때문이다. 그래서 상태를 Activity 가 아니라 여기에 두고, 화면은 이걸
 * 들여다보기만 한다.
 */
object MirrorRuntime {

    private const val MAX_LOG = 400
    private const val MAX_FAILURES = 60
    private const val MAX_GESTURES = 50

    private val _state = MutableStateFlow(MirrorState.IDLE)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _failures = MutableStateFlow<List<MirrorFailure>>(emptyList())
    val failures: StateFlow<List<MirrorFailure>> = _failures.asStateFlow()

    private val _gestureLog = MutableStateFlow<List<String>>(emptyList())
    val gestureLog: StateFlow<List<String>> = _gestureLog.asStateFlow()

    private val _gestures = MutableStateFlow<List<GestureSummary>>(emptyList())
    val gestures: StateFlow<List<GestureSummary>> = _gestures.asStateFlow()

    /** 대상 이름 → 마지막 결과. 대상마다 **따로** 기록한다. */
    private val _targetResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val targetResults: StateFlow<Map<String, String>> = _targetResults.asStateFlow()

    /** 시작을 막은 이유. 통과하면 null. */
    private val _blockedReason = MutableStateFlow<String?>(null)
    val blockedReason: StateFlow<String?> = _blockedReason.asStateFlow()

    internal fun setState(next: MirrorState) {
        _state.value = next
    }

    internal fun setBlockedReason(reason: String?) {
        _blockedReason.value = reason
    }

    internal fun addFailure(failure: MirrorFailure) {
        _failures.value = (_failures.value + failure).takeLast(MAX_FAILURES)
    }

    internal fun addLog(line: String) {
        _gestureLog.value = (_gestureLog.value + line).takeLast(MAX_LOG)
    }

    internal fun addGesture(summary: GestureSummary) {
        _gestures.value = (_gestures.value + summary).takeLast(MAX_GESTURES)
        if (summary.targetResults.isNotEmpty()) {
            _targetResults.value = _targetResults.value + summary.targetResults
        }
    }

    internal fun setTargetResult(name: String, result: String) {
        _targetResults.value = _targetResults.value + (name to result)
    }

    fun clearRecords() {
        _failures.value = emptyList()
        _gestureLog.value = emptyList()
        _gestures.value = emptyList()
        _targetResults.value = emptyMap()
    }

    fun dumpGestures(): String = _gestures.value.joinToString("\n\n") { it.describe() }
}
