package com.macromobile.imagemacro.storage

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 앱 내부 저장소의 폴더 구조를 담당한다.
 *
 * ```
 * files/
 *  ├─ macros/      매크로 JSON
 *  ├─ templates/<macroId>/   사용자가 등록한 버튼 이미지
 *  ├─ targets/<macroId>/     사용자가 등록한 타겟 카드 이미지
 *  ├─ screenshots/           타겟 발견 등 결과 화면
 *  └─ logs/                  디버그용 실패 화면
 * ```
 *
 * 앱에는 어떤 기본 이미지도 들어 있지 않다. 모든 파일은 사용자가 만든 것이다.
 */
class TemplateFiles(context: Context) {

    private val root: File = context.filesDir

    val macrosDir: File get() = ensure(File(root, "macros"))
    val screenshotsDir: File get() = ensure(File(root, "screenshots"))
    val logsDir: File get() = ensure(File(root, "logs"))

    fun templatesDir(macroId: String): File = ensure(File(File(root, "templates"), safe(macroId)))

    fun targetsDir(macroId: String): File = ensure(File(File(root, "targets"), safe(macroId)))

    fun templateFile(macroId: String, fileName: String): File =
        File(templatesDir(macroId), safe(fileName))

    fun targetFile(macroId: String, fileName: String): File =
        File(targetsDir(macroId), safe(fileName))

    fun macroFile(macroId: String): File = File(macrosDir, "${safe(macroId)}.json")

    /**
     * 잘라낸 이미지를 PNG 로 저장하고 파일 이름을 돌려준다.
     *
     * @return 저장에 실패하면 null
     */
    fun saveTemplateImage(macroId: String, bitmap: Bitmap, asTarget: Boolean): String? {
        val dir = if (asTarget) targetsDir(macroId) else templatesDir(macroId)
        val name = "${UUID.randomUUID()}.png"
        return if (writePng(File(dir, name), bitmap)) name else null
    }

    /** 결과/디버그 화면을 저장한다. 실패해도 매크로 동작에는 영향을 주지 않는다. */
    fun saveScreenshot(bitmap: Bitmap, prefix: String, intoLogs: Boolean = false): File? {
        val dir = if (intoLogs) logsDir else screenshotsDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${safe(prefix)}_$stamp.png")
        return if (writePng(file, bitmap)) file else null
    }

    fun deleteTemplateImage(macroId: String, fileName: String, asTarget: Boolean) {
        val f = if (asTarget) targetFile(macroId, fileName) else templateFile(macroId, fileName)
        runCatching { if (f.exists()) f.delete() }
    }

    /** 매크로를 지울 때 딸린 이미지 폴더까지 정리한다. */
    fun deleteMacroFiles(macroId: String) {
        runCatching { templatesDir(macroId).deleteRecursively() }
        runCatching { targetsDir(macroId).deleteRecursively() }
        runCatching { macroFile(macroId).delete() }
    }

    /** 오래된 로그/스크린샷을 정리한다. */
    fun pruneOldFiles(keepCount: Int = 200) {
        listOf(logsDir, screenshotsDir).forEach { dir ->
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@forEach
            files.drop(keepCount).forEach { runCatching { it.delete() } }
        }
    }

    fun totalStoredBytes(): Long =
        listOf(macrosDir, screenshotsDir, logsDir, File(root, "templates"), File(root, "targets"))
            .sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    private fun writePng(file: File, bitmap: Bitmap): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "이미지 저장 실패: ${file.name}", e)
        false
    }

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 경로 조작(`../`)과 파일명에 못 쓰는 문자를 막는다. */
    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unnamed" }

    private companion object {
        const val TAG = "TemplateFiles"
    }
}
