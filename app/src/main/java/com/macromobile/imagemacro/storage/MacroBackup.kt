package com.macromobile.imagemacro.storage

import android.util.Log
import com.macromobile.imagemacro.model.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 매크로 하나를 파일 한 개로 내보내고 다시 가져온다.
 *
 * 앱을 지웠다 깔거나 기기를 바꿔도 공들여 만든 매크로가 사라지지 않게 하는 장치다.
 * 단계·설정뿐 아니라 **등록한 이미지까지 함께** 담는다.
 *
 * 파일 구조(zip):
 * ```
 * macro.json          매크로 정의
 * templates/<파일명>   등록한 버튼 이미지
 * targets/<파일명>     등록한 타겟 카드 이미지
 * ```
 */
class MacroBackup(
    private val files: TemplateFiles,
    private val repository: MacroRepository,
) {

    /** 매크로와 이미지를 zip 으로 내보낸다. */
    suspend fun export(macro: Macro, out: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY_MACRO))
                zip.write(repository.exportJson(macro).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                macro.templates.forEach { template ->
                    addFile(zip, "$DIR_TEMPLATES/${template.fileName}", files.templateFile(macro.id, template.fileName))
                }
                macro.targets.forEach { target ->
                    addFile(zip, "$DIR_TARGETS/${target.fileName}", files.targetFile(macro.id, target.fileName))
                }
            }
        }.onFailure { Log.e(TAG, "매크로 내보내기 실패", it) }
    }

    /**
     * zip 에서 매크로를 복원한다.
     *
     * 이미 있는 매크로를 덮어쓰지 않도록 항상 **새 매크로**로 추가한다.
     */
    suspend fun import(input: InputStream): Result<Macro> = withContext(Dispatchers.IO) {
        runCatching {
            val newMacroId = UUID.randomUUID().toString()
            val jsonHolder = arrayOfNulls<String>(1)
            val templateDir = files.templatesDir(newMacroId)
            val targetDir = files.targetsDir(newMacroId)

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry: ZipEntry? = zip.getNextEntry()
                while (entry != null) {
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == ENTRY_MACRO -> jsonHolder[0] = zip.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("$DIR_TEMPLATES/") ->
                            writeEntry(zip, templateDir, name.removePrefix("$DIR_TEMPLATES/"))
                        name.startsWith("$DIR_TARGETS/") ->
                            writeEntry(zip, targetDir, name.removePrefix("$DIR_TARGETS/"))
                        else -> Log.w(TAG, "알 수 없는 항목은 건너뜁니다: $name")
                    }
                    zip.closeEntry()
                    entry = zip.getNextEntry()
                }
            }

            val text = jsonHolder[0] ?: throw IllegalArgumentException(
                "매크로 파일이 아닙니다. 이 앱에서 내보낸 파일을 선택해주세요.",
            )
            val parsed = repository.parseJson(text).getOrElse {
                throw IllegalArgumentException("매크로 내용을 읽지 못했습니다. 파일이 손상되었을 수 있습니다.")
            }
            val restored = parsed.copy(
                id = newMacroId,
                name = parsed.displayName(),
                createdAt = 0L,
            )
            repository.save(restored)
        }.onFailure {
            Log.e(TAG, "매크로 가져오기 실패", it)
        }
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists()) {
            Log.w(TAG, "이미지 파일이 없어 건너뜁니다: ${file.name}")
            return
        }
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * zip 안의 파일 하나를 꺼내 쓴다.
     *
     * 항목 이름에 `../` 같은 경로가 들어 있어도 폴더 밖으로 나가지 않도록 이름만 쓴다.
     */
    private fun writeEntry(zip: ZipInputStream, dir: File, rawName: String) {
        val safeName = File(rawName).name
        if (safeName.isBlank() || safeName == "." || safeName == "..") return
        val target = File(dir, safeName)
        // 만들어진 경로가 정말 대상 폴더 안인지 확인한다(zip 경로 조작 방지).
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            Log.w(TAG, "폴더 밖을 가리키는 항목은 건너뜁니다: $rawName")
            return
        }
        target.outputStream().use { zip.copyTo(it) }
    }

    private companion object {
        const val TAG = "MacroBackup"
        const val ENTRY_MACRO = "macro.json"
        const val DIR_TEMPLATES = "templates"
        const val DIR_TARGETS = "targets"
    }
}
