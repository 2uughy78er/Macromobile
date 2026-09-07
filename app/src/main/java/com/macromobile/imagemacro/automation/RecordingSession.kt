package com.macromobile.imagemacro.automation

import android.util.Log
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.storage.MacroRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 녹화가 끝났을 때의 결과. */
sealed interface RecordingResult {
    /** [addedSteps] 개의 단계를 [macroName] 에 붙였다. */
    data class Saved(val macroName: String, val addedSteps: Int) : RecordingResult
    data class Empty(val message: String) : RecordingResult
    data class Failed(val message: String) : RecordingResult
}

/**
 * 한 번의 녹화 동안 모은 동작을 들고 있다가, 끝나면 매크로 뒤에 단계로 붙인다.
 *
 * 기존 단계를 건드리지 않고 **항상 뒤에 추가**한다. 녹화가 마음에 들지 않으면
 * 매크로 편집에서 그 부분만 지우면 된다.
 */
class RecordingSession(private val repository: MacroRepository) {

    private val gestures = ArrayList<RecordedGesture>()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** 지금 녹화 중인 매크로. 녹화 중에는 바뀌지 않는다. */
    private var macroId: String = ""
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f

    @Synchronized
    fun start(macro: Macro, screenWidth: Int, screenHeight: Int, density: Float) {
        gestures.clear()
        macroId = macro.id
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.density = density
        _count.value = 0
        _recording.value = true
    }

    @Synchronized
    fun add(gesture: RecordedGesture) {
        if (!_recording.value) return
        gestures += gesture
        _count.value = gestures.size
    }

    /**
     * 녹화를 끝내고 모은 동작을 매크로에 붙인다.
     *
     * 화면 크기를 모르면(캡처가 꺼져 있었다면) 좌표를 기준 해상도로 바꿀 수 없으므로
     * 저장하지 않고 그 사실을 알린다.
     */
    suspend fun stopAndSave(): RecordingResult {
        val recorded = takeRecorded()

        if (recorded.isEmpty()) {
            return RecordingResult.Empty("녹화된 동작이 없습니다.")
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            return RecordingResult.Failed("화면 크기를 알 수 없어 좌표를 저장하지 못했습니다.")
        }
        val macro = repository.get(macroId)
            ?: return RecordingResult.Failed("녹화할 매크로를 찾지 못했습니다.")

        return try {
            // 기준 해상도가 비어 있는 새 매크로면 지금 화면 크기를 기준으로 삼는다.
            val referenceWidth = if (macro.referenceWidth > 0) macro.referenceWidth else screenWidth
            val referenceHeight =
                if (macro.referenceHeight > 0) macro.referenceHeight else screenHeight
            val mapper = MacroRecorder.mapperFor(
                referenceWidth, referenceHeight, screenWidth, screenHeight,
            )
            val steps = MacroRecorder.toSteps(
                gestures = recorded,
                mapper = mapper,
                density = density,
                startIndex = 1,
            )
            if (steps.isEmpty()) {
                return RecordingResult.Empty("저장할 동작이 없습니다.")
            }
            val saved = repository.save(
                macro.copy(
                    steps = macro.steps + steps,
                    referenceWidth = referenceWidth,
                    referenceHeight = referenceHeight,
                ),
            )
            RecordingResult.Saved(saved.displayName(), steps.size)
        } catch (e: Exception) {
            Log.e(TAG, "녹화 저장 실패", e)
            RecordingResult.Failed("녹화한 동작을 저장하지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
        }
    }

    /** 모은 동작을 꺼내오고 녹화를 끝낸다. 저장은 이 잠금 밖에서 한다. */
    @Synchronized
    private fun takeRecorded(): List<RecordedGesture> {
        _recording.value = false
        val recorded = ArrayList(gestures)
        gestures.clear()
        _count.value = 0
        return recorded
    }

    /** 저장하지 않고 버린다. */
    @Synchronized
    fun cancel() {
        gestures.clear()
        _count.value = 0
        _recording.value = false
    }

    private companion object {
        const val TAG = "RecordingSession"
    }
}
