package com.macromobile.imagemacro.storage

import android.util.Log
import com.macromobile.imagemacro.model.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 매크로를 JSON 파일로 저장/불러오기 한다.
 *
 * 매크로 하나가 파일 하나(`macros/<id>.json`)에 대응한다. 파일이 깨져도 나머지 매크로는
 * 그대로 열리고, 사용자가 매크로를 내보내거나 가져오기 쉽다.
 *
 * 앱을 처음 설치하면 목록은 **비어 있다**. 기본 매크로는 제공하지 않는다.
 */
class MacroRepository(private val files: TemplateFiles) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val writeLock = Mutex()
    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun refresh(): List<Macro> = withContext(Dispatchers.IO) {
        val list = files.macrosDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { readFile(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        _macros.value = list
        _loaded.value = true
        list
    }

    fun get(id: String): Macro? = _macros.value.firstOrNull { it.id == id }

    suspend fun save(macro: Macro): Macro = withContext(Dispatchers.IO) {
        val stamped = macro.copy(
            updatedAt = System.currentTimeMillis(),
            createdAt = if (macro.createdAt == 0L) System.currentTimeMillis() else macro.createdAt,
        )
        writeLock.withLock { writeFile(stamped) }
        _macros.value = (_macros.value.filterNot { it.id == stamped.id } + stamped)
            .sortedByDescending { it.updatedAt }
        stamped
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writeLock.withLock { files.deleteMacroFiles(id) }
        _macros.value = _macros.value.filterNot { it.id == id }
    }

    /** 매크로를 복제한다. 이미지 파일도 함께 복사해 원본과 독립적으로 만든다. */
    suspend fun duplicate(source: Macro): Macro = withContext(Dispatchers.IO) {
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = source.displayName() + " (복사본)",
            createdAt = 0L,
        )
        copyImages(source.id, copy.id)
        save(copy)
    }

    private fun copyImages(fromMacroId: String, toMacroId: String) {
        runCatching {
            files.templatesDir(fromMacroId).copyRecursively(files.templatesDir(toMacroId), true)
            files.targetsDir(fromMacroId).copyRecursively(files.targetsDir(toMacroId), true)
        }.onFailure { Log.e(TAG, "이미지 복사 실패", it) }
    }

    /** JSON 문자열로 내보낸다(이미지는 포함되지 않는다). */
    fun exportJson(macro: Macro): String = json.encodeToString(Macro.serializer(), macro)

    /** JSON 문자열에서 매크로 구조를 읽는다. 이미지 파일은 별도로 채워야 한다. */
    fun parseJson(text: String): Result<Macro> = runCatching {
        json.decodeFromString(Macro.serializer(), text)
    }

    private fun readFile(file: File): Macro? = try {
        json.decodeFromString(Macro.serializer(), file.readText())
    } catch (e: Exception) {
        Log.e(TAG, "매크로 파일을 읽지 못했습니다: ${file.name}", e)
        null
    }

    private fun writeFile(macro: Macro) {
        val target = files.macroFile(macro.id)
        // 저장 도중 앱이 죽어도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 교체한다.
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(Macro.serializer(), macro))
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "MacroRepository"
    }
}
