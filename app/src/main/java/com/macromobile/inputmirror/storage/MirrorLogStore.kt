package com.macromobile.inputmirror.storage

import android.content.Context
import android.util.Log
import com.macromobile.inputmirror.input.MirrorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 미러링 기록을 화면에 보여주고 파일로 남긴다.
 *
 * 좌표와 결과만 남기며, 개인정보나 계정 정보는 담지 않는다(요구사항 14번).
 */
class MirrorLogStore(context: Context) {

    private val logDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val _records = MutableStateFlow<List<MirrorRecord>>(emptyList())
    val records: StateFlow<List<MirrorRecord>> = _records.asStateFlow()

    private var currentFile: File? = null

    @Volatile
    var writeToFile: Boolean = true

    /** 새 파일을 열고 기록을 시작한다. */
    @Synchronized
    fun startSession() {
        _records.value = emptyList()
        currentFile = if (writeToFile) {
            File(logDir, "mirror_${fileFormat.format(Date())}.log").also { file ->
                runCatching {
                    file.appendText("# Input Mirror 검증 로그\n")
                    file.appendText(
                        "# 시각\t제스처\t종류\t단계\t마스터X,Y\t대상별 좌표\t지연(ms)\t결과\n",
                    )
                }.onFailure { Log.e(TAG, "로그 파일을 만들지 못했습니다", it) }
            }
        } else {
            null
        }
    }

    @Synchronized
    fun add(record: MirrorRecord) {
        _records.value = (_records.value + record).takeLast(MAX_IN_MEMORY)
        val file = currentFile ?: return
        runCatching { file.appendText(format(record) + "\n") }
            .onFailure { Log.w(TAG, "로그를 쓰지 못했습니다", it) }
    }

    /**
     * 제스처 로그 한 줄을 파일에 그대로 남긴다.
     *
     * 판정 과정(DOWN / MOVE 거리 / 상태 전환 / FINAL_TYPE)은 결과 표와 형태가 달라
     * [MirrorRecord] 에 담지 않는다. 대신 같은 파일에 그대로 적어, 나중에 로그 하나만
     * 보고도 "왜 이렇게 판정됐는지"를 따라갈 수 있게 한다.
     */
    @Synchronized
    fun note(text: String) {
        val file = currentFile ?: return
        runCatching { file.appendText(text + "\n") }
            .onFailure { Log.w(TAG, "제스처 로그를 쓰지 못했습니다", it) }
    }

    @Synchronized
    fun stopSession() {
        currentFile = null
    }

    fun clear() {
        _records.value = emptyList()
    }

    /** 한 줄 포맷. 화면과 파일이 같은 형태를 쓴다. */
    fun format(record: MirrorRecord): String {
        val targets = record.targets.entries.joinToString(" | ") { (name, p) ->
            "$name: ${p.x.toInt()},${p.y.toInt()}"
        }.ifBlank { "-" }
        val result = if (record.success) "SUCCESS" else "FAIL(${record.error ?: "알 수 없음"})"
        return "${timeFormat.format(Date(record.timestamp))}\t#${record.gestureId}\t" +
            "${record.gestureType.label}\t${record.phase.label}\t" +
            "${record.masterX.toInt()},${record.masterY.toInt()}\t$targets\t" +
            "${record.latencyMs}ms\t$result"
    }

    fun logFiles(): List<File> =
        logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteAllLogs() {
        logDir.listFiles()?.forEach { runCatching { it.delete() } }
        currentFile = null
    }

    fun totalBytes(): Long = logFiles().sumOf { it.length() }

    private companion object {
        const val TAG = "MirrorLogStore"
        const val MAX_IN_MEMORY = 400
    }
}
