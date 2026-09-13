package com.macromobile.inputmirror.diag

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 기록 한 줄.
 *
 * 요청받은 항목을 빠짐없이 담는다: 시각 / 스레드 / 컴포넌트 / 이벤트 / 예외 클래스 / 메시지.
 * 스택 트레이스는 따로 보관해 화면에서 펼쳐 볼 수 있게 한다.
 */
data class DiagRecord(
    val timestampMs: Long,
    val threadName: String,
    val component: String,
    val event: String,
    val outcome: DiagOutcome,
    val detail: String = "",
    val exceptionClass: String = "",
    val exceptionMessage: String = "",
    val stackTrace: String = "",
) {
    val isFailure: Boolean get() = outcome == DiagOutcome.FAILED

    /** 한 줄 요약. 로그 파일에도 같은 형식으로 쓴다. */
    fun line(): String = buildString {
        append(TIME_FORMAT.format(Date(timestampMs)))
        append(" [").append(threadName).append(']')
        append(" ").append(component)
        append(" · ").append(event)
        append(" · ").append(outcome.name)
        if (detail.isNotBlank()) append(" · ").append(detail)
        if (exceptionClass.isNotBlank()) {
            append("\n    예외: ").append(exceptionClass)
            append(": ").append(exceptionMessage.ifBlank { "(메시지 없음)" })
        }
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * 단계 기록기.
 *
 * 크래시 원인을 찾는 데 필요한 것은 "어디까지 갔는가" 와 "무엇이 던져졌는가" 두 가지다.
 * 이 객체는 그 둘만 정확히 남긴다. 예외를 대신 삼켜주지 않는다 — [runStage] 는 기록한 뒤
 * 그대로 다시 던진다. 크래시를 숨기면 원인을 영영 못 찾기 때문이다.
 *
 * 파일에는 기록할 때마다 바로 쓴다. 크래시로 프로세스가 죽어도 마지막 줄이 남아야 하므로
 * 버퍼에 모아두지 않는다.
 */
object DiagLog {

    private const val TAG = "InputMirrorDiag"
    private const val MAX_IN_MEMORY = 400
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val _records = MutableStateFlow<List<DiagRecord>>(emptyList())
    val records: StateFlow<List<DiagRecord>> = _records.asStateFlow()

    @Volatile
    private var logFile: File? = null

    /** 마지막으로 시작된 단계. 크래시 보고서에 "어디서 죽었는지" 로 들어간다. */
    @Volatile
    var lastStage: DiagStage? = null
        private set

    @Volatile
    var lastComponent: String = "(없음)"
        private set

    fun init(context: Context) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        val file = File(dir, "stages.log")
        if (file.length() > MAX_FILE_BYTES) file.delete()
        logFile = file
        record(
            component = "App",
            event = "DIAG_INIT",
            outcome = DiagOutcome.OK,
            detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    /** 단계 시작을 남긴다. */
    fun start(stage: DiagStage, component: String, detail: String = "") {
        lastStage = stage
        lastComponent = component
        record(component, stage.name, DiagOutcome.STARTED, detail)
    }

    /** 단계 성공을 남긴다. */
    fun ok(stage: DiagStage, component: String, detail: String = "") {
        record(component, stage.name, DiagOutcome.OK, detail)
    }

    /** 단계를 건너뛴 이유를 남긴다. STEP 선택으로 꺼둔 단계가 여기에 들어간다. */
    fun skip(stage: DiagStage, component: String, reason: String) {
        record(component, stage.name, DiagOutcome.SKIPPED, reason)
    }

    /** 단계 실패를 스택 트레이스까지 남긴다. 예외를 삼키지는 않는다. */
    fun fail(stage: DiagStage, component: String, error: Throwable, detail: String = "") {
        record(
            component = component,
            event = stage.name,
            outcome = DiagOutcome.FAILED,
            detail = detail,
            error = error,
        )
    }

    /**
     * 단계를 실행하면서 앞뒤로 기록한다.
     *
     * 예외가 나면 **기록한 뒤 그대로 다시 던진다.** 이 함수는 크래시 방지용이 아니라
     * 크래시 지점 확인용이다.
     */
    fun <T> runStage(stage: DiagStage, component: String, block: () -> T): T {
        start(stage, component)
        return try {
            val value = block()
            ok(stage, component)
            value
        } catch (error: Throwable) {
            fail(stage, component, error)
            throw error
        }
    }

    /**
     * 단계를 실행하되, 실패해도 화면 진입을 막지 않는다.
     *
     * **진단 목적의 읽기에만 쓴다.** (화면 정보 조회처럼 실패해도 앱 기능에 지장이 없고,
     * 오히려 무엇이 실패했는지를 보여주는 편이 나은 경우.) 실패는 조용히 넘어가지 않고
     * 기록으로 남아 화면과 로그 파일에 그대로 보인다.
     */
    fun <T> tryStage(stage: DiagStage, component: String, block: () -> T): Result<T> {
        start(stage, component)
        return try {
            val value = block()
            ok(stage, component)
            Result.success(value)
        } catch (error: Throwable) {
            fail(stage, component, error, detail = "진단용 읽기 실패 — 화면은 계속 진행합니다")
            Result.failure(error)
        }
    }

    fun record(
        component: String,
        event: String,
        outcome: DiagOutcome,
        detail: String = "",
        error: Throwable? = null,
    ) {
        val entry = DiagRecord(
            timestampMs = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            component = component,
            event = event,
            outcome = outcome,
            detail = detail,
            exceptionClass = error?.let { it::class.java.name } ?: "",
            exceptionMessage = error?.message ?: "",
            stackTrace = error?.let { stackTraceOf(it) } ?: "",
        )
        synchronized(this) {
            val next = _records.value + entry
            _records.value = if (next.size > MAX_IN_MEMORY) {
                next.subList(next.size - MAX_IN_MEMORY, next.size)
            } else {
                next
            }
            appendToFile(entry)
        }
        when (outcome) {
            DiagOutcome.FAILED -> Log.e(TAG, entry.line())
            else -> Log.i(TAG, entry.line())
        }
    }

    fun clear() {
        synchronized(this) {
            _records.value = emptyList()
            logFile?.delete()
        }
    }

    fun dump(): String = _records.value.joinToString("\n") { it.line() }

    fun stageLogFile(): File? = logFile

    private fun appendToFile(entry: DiagRecord) {
        val file = logFile ?: return
        try {
            file.appendText(entry.line() + "\n")
            if (entry.stackTrace.isNotBlank()) file.appendText(entry.stackTrace + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "진단 로그를 파일에 쓰지 못했습니다", e)
        }
    }

    fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }
}
