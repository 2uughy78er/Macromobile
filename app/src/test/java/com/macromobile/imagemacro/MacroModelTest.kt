package com.macromobile.imagemacro

import com.macromobile.imagemacro.model.ActionType
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.model.MacroStep
import com.macromobile.imagemacro.model.RepeatSettings
import com.macromobile.imagemacro.model.Roi
import com.macromobile.imagemacro.model.Target
import com.macromobile.imagemacro.model.Template
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroModelTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `매크로를 JSON 으로 저장하고 다시 읽을 수 있다`() {
        val macro = Macro(
            name = "테스트",
            referenceWidth = 1080,
            referenceHeight = 2400,
            steps = listOf(
                MacroStep(type = ActionType.WAIT_AND_TAP, name = "시작", templateIds = listOf("t1")),
                MacroStep(type = ActionType.WAIT, waitMs = 2_000),
            ),
            templates = listOf(Template(id = "t1", name = "버튼", fileName = "t1.png")),
            targets = listOf(Target(id = "g1", name = "카드", fileName = "g1.png")),
        )
        val text = json.encodeToString(Macro.serializer(), macro)
        val back = json.decodeFromString(Macro.serializer(), text)
        assertEquals(macro.name, back.name)
        assertEquals(2, back.steps.size)
        assertEquals("버튼", back.template("t1")?.name)
        assertEquals("카드", back.target("g1")?.name)
    }

    @Test
    fun `모르는 필드가 있어도 읽을 수 있다`() {
        // 앞으로 필드가 늘어나도 예전 파일을 계속 열 수 있어야 한다.
        val text = """{"id":"x","name":"옛날 매크로","futureField":123,"steps":[]}"""
        val macro = json.decodeFromString(Macro.serializer(), text)
        assertEquals("옛날 매크로", macro.name)
        assertTrue(macro.steps.isEmpty())
    }

    @Test
    fun `사용 안 함으로 꺼둔 단계는 실행 목록에서 빠진다`() {
        val macro = Macro(
            steps = listOf(
                MacroStep(type = ActionType.WAIT, enabled = true),
                MacroStep(type = ActionType.WAIT, enabled = false),
            ),
        )
        assertEquals(1, macro.enabledSteps.size)
    }

    @Test
    fun `반복 횟수가 음수면 무한 반복이다`() {
        assertTrue(RepeatSettings(count = -1).isInfinite)
        assertFalse(RepeatSettings(count = 3).isInfinite)
    }

    @Test
    fun `ROI 는 두 점에서 만들 수 있고 순서를 신경쓰지 않는다`() {
        val a = Roi.fromBounds(100, 200, 300, 400)
        val b = Roi.fromBounds(300, 400, 100, 200)
        assertEquals(a, b)
        assertEquals(100, a.x)
        assertEquals(200, a.width)
        assertTrue(a.isValid)
    }

    @Test
    fun `이미지가 필요한 동작만 usesTemplates 가 참이다`() {
        assertTrue(ActionType.WAIT_AND_TAP.usesTemplates)
        assertTrue(ActionType.TAP_IF_FOUND.usesTemplates)
        assertFalse(ActionType.WAIT.usesTemplates)
        assertFalse(ActionType.TARGET_CHECK.usesTemplates)
    }

    @Test
    fun `새 매크로는 이미지도 단계도 없이 비어 있다`() {
        // 앱에는 어떤 기본 데이터도 들어 있지 않아야 한다.
        val macro = Macro()
        assertTrue(macro.steps.isEmpty())
        assertTrue(macro.templates.isEmpty())
        assertTrue(macro.targets.isEmpty())
        assertTrue(macro.initialVariables.isEmpty())
    }
}
