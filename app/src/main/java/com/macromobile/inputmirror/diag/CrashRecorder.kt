package com.macromobile.inputmirror.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 저장된 크래시 보고서 하나. */
data class CrashReport(
    val fileName: String,
    val timestampMs: Long,
    val threadName: String,
    val component: String,
    val event: String,
    val exceptionClass: String,
    val exceptionMessage: String,
    val body: String,
) {
    val headline: String
        get() = "$exceptionClass: ${exceptionMessage.ifBlank { "(메시지 없음)" }}"
}

/**
 * 크래시를 기록하는 장치.
 *
 * **크래시를 막지 않는다.** 기본 핸들러를 가로채 보고서를 파일에 남긴 뒤, 원래 핸들러에
 * 그대로 넘겨 프로세스가 평소처럼 죽게 둔다. 예외를 삼켜 앱을 억지로 살려두면 원인이
 * 가려지고, 그 상태의 화면은 어차피 신뢰할 수 없기 때문이다.
 *
 * 남기는 내용은 요청받은 항목 그대로다:
 * 시각 / 스레드 / 컴포넌트 / 이벤트(마지막 단계) / 예외 클래스 / 메시지 / 스택 트레이스.
 * 여기에 기기와 OS 정보를 덧붙인다. Android 16 · 삼성 기기처럼 특정 환경에서만 나는
 * 문제를 구분하려면 그 정보가 반드시 함께 있어야 한다.
 */
object CrashRecorder {

    private const val MAX_REPORTS = 20
    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val HUMAN = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    fun install(context: Context) {
        val reportDir = File(context.filesDir, "crash").apply { mkdirs() }
        dir = reportDir

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(reportDir, thread, error) }
            // 원래 동작을 그대로 돌려준다. 크래시는 크래시대로 나야 한다.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(reportDir: File, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val stage = DiagLog.lastStage
        val body = buildString {
            appendLine("시각        : ${HUMAN.format(Date(now))}")
            appendLine("스레드      : ${thread.name} (id=${thread.id})")
            appendLine("컴포넌트    : ${DiagLog.lastComponent}")
            appendLine("이벤트      : ${stage?.name ?: "(단계 기록 없음)"}" +
                (stage?.let { " — ${it.label}" } ?: ""))
            appendLine("예외 클래스 : ${error::class.java.name}")
            appendLine("예외 메시지 : ${error.message ?: "(메시지 없음)"}")
            appendLine("기기        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("OS          : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("--- 스택 트레이스 ---")
            appendLine(DiagLog.stackTraceOf(error))
            appendLine("--- 크래시 직전 단계 기록 ---")
            appendLine(DiagLog.dump().lines().takeLast(40).joinToString("\n"))
        }
        File(reportDir, "crash-${STAMP.format(Date(now))}.txt").writeText(body)
        prune(reportDir)
    }

    private fun prune(reportDir: File) {
        val files = reportDir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size <= MAX_REPORTS) return
        files.take(files.size - MAX_REPORTS).forEach { it.delete() }
    }

    /** 저장된 보고서를 최신순으로 읽는다. 앱을 다시 켜면 이 목록이 화면에 뜬다. */
    fun reports(): List<CrashReport> {
        val reportDir = dir ?: return emptyList()
        val files = reportDir.listFiles()?.sortedByDescending { it.name } ?: return emptyList()
        return files.mapNotNull { file ->
            val body = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            CrashReport(
                fileName = file.name,
                timestampMs = file.lastModified(),
                threadName = field(body, "스레드"),
                component = field(body, "컴포넌트"),
                event = field(body, "이벤트"),
                exceptionClass = field(body, "예외 클래스"),
                exceptionMessage = field(body, "예외 메시지"),
                body = body,
            )
        }
    }

    fun hasReports(): Boolean = (dir?.listFiles()?.size ?: 0) > 0

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    private fun field(body: String, name: String): String =
        body.lineSequence()
            .firstOrNull { it.startsWith(name) && it.contains(':') }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
}
