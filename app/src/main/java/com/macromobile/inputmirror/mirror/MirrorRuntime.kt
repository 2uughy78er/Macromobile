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

    /** 엔진 밖(화면 등)에서 실패를 남길 때. 기록되는 곳은 한 군데뿐이어야 한다. */
    fun addFailureExternal(failure: MirrorFailure) = addFailure(failure)

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

/**
 * 주입 가능성 측정 결과 한 묶음.
 *
 * "3개 동시에 되나요?" 에 대한 답을 짐작이 아니라 숫자로 남긴다.
 */
data class ProbeResult(
    val mode: com.macromobile.inputmirror.model.DispatchMode,
    val attempts: Int,
    /** 대상 이름 → (결과 이름 → 횟수). 대상마다 **따로** 센다. */
    val perTarget: Map<String, Map<String, Int>>,
) {
    /** 모든 대상이 매번 성공했는가. 이것이 참일 때만 "동시 주입이 된다"고 말할 수 있다. */
    val allCompleted: Boolean
        get() = perTarget.isNotEmpty() && perTarget.values.all { counts ->
            counts["COMPLETED"] == attempts
        }

    fun summary(): String = buildString {
        append(if (allCompleted) "✔ " else "✘ ")
        append(mode.name).append(" ×").append(attempts)
        if (perTarget.isEmpty()) {
            append("  — 결과 없음 (대상에 도달하지 못했습니다)")
            return@buildString
        }
        perTarget.forEach { (name, counts) ->
            append("\n    ").append(name).append(": ")
            append(counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    }
}

/**
 * 연속 입력 재현 테스트 결과.
 *
 * 중요한 것은 성공률이 아니라 **처음 실패한 회차**다. "한동안 되다가 깨진다"는 증상은
 * 그 번호가 있어야 좁힐 수 있다.
 */
data class StressResult(
    val name: String,
    val rounds: Int,
    /** 처음 실패한 회차(1부터). 끝까지 성공했으면 null. */
    val firstFailureAt: Int?,
    val perTarget: Map<String, Map<String, Int>>,
) {
    val allOk: Boolean get() = firstFailureAt == null && perTarget.isNotEmpty()

    fun summary(): String = buildString {
        append(if (allOk) "✔ " else "✘ ")
        append(name)
        append(if (firstFailureAt == null) "  전부 성공" else "  ${firstFailureAt}회차부터 실패")
        perTarget.forEach { (target, counts) ->
            append("\n    ").append(target).append(": ")
            append(counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    }
}
