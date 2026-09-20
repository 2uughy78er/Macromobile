package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.Template
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 백업 파일 형식이 왕복(내보내기 → 가져오기)에서 깨지지 않는지 확인한다.
 *
 * 파일 입출력과 안드로이드 API 를 쓰는 [com.macromobile.imagemacro.storage.MacroBackup] 자체는
 * 기기에서만 돌릴 수 있으므로, 여기서는 형식과 직렬화 규칙만 검증한다.
 */
class MacroBackupFormatTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleMacro() = Macro(
        id = "원래-아이디",
        name = "사용자 매크로",
        referenceWidth = 2304,
        referenceHeight = 1440,
        steps = listOf(
            MacroStep(type = ActionType.WAIT_AND_TAP, name = "첫 단계", templateIds = listOf("t1")),
            MacroStep(type = ActionType.WAIT, waitMs = 2_000),
        ),
        templates = listOf(Template(id = "t1", name = "버튼", fileName = "a.png")),
        targets = listOf(Target(id = "g1", name = "카드", fileName = "b.png")),
    )

    @Test
    fun `zip 에 담았다 꺼내도 매크로 내용이 그대로다`() {
        val macro = sampleMacro()

        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("macro.json"))
                zip.write(json.encodeToString(Macro.serializer(), macro).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("templates/a.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("targets/b.png"))
                zip.write(byteArrayOf(4, 5))
                zip.closeEntry()
            }
        }.toByteArray()

        var text: String? = null
        val images = mutableMapOf<String, Int>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.getNextEntry()
            while (e != null) {
                when {
                    e.name == "macro.json" -> text = zip.readBytes().toString(Charsets.UTF_8)
                    else -> images[e.name] = zip.readBytes().size
                }
                e = zip.getNextEntry()
            }
        }

        val restored = json.decodeFromString(Macro.serializer(), requireNotNull(text))
        assertEquals(macro.name, restored.name)
        assertEquals(2, restored.steps.size)
        assertEquals("버튼", restored.template("t1")?.name)
        assertEquals("카드", restored.target("g1")?.name)
        assertEquals(2304, restored.referenceWidth)
        assertEquals(3, images["templates/a.png"])
        assertEquals(2, images["targets/b.png"])
    }

    @Test
    fun `가져온 매크로는 새 아이디를 받아 기존 것을 덮어쓰지 않는다`() {
        val macro = sampleMacro()
        val imported = macro.copy(id = "새-아이디", createdAt = 0L)
        assertNotEquals(macro.id, imported.id)
        // 템플릿·타겟 아이디는 매크로 안에서만 쓰이므로 그대로 유지되어야 단계 연결이 살아남는다.
        assertEquals("t1", imported.templates.first().id)
        assertTrue(imported.steps.first().templateIds.contains("t1"))
    }

    @Test
    fun `경로 조작이 들어간 항목 이름은 파일 이름만 남는다`() {
        // zip 항목 이름에 상위 폴더가 들어와도 폴더 밖으로 나가면 안 된다.
        listOf("../../evil.png", "/etc/passwd", "sub/dir/ok.png").forEach { raw ->
            val safe = java.io.File(raw).name
            assertTrue("'$raw' 에서 경로가 남았습니다: $safe", !safe.contains('/'))
            assertTrue(!safe.contains(".."))
        }
    }
}
