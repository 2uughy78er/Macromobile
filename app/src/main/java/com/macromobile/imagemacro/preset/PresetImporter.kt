package com.macromobile.imagemacro.preset

import android.content.Context
import android.util.Log
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.storage.TemplateFiles
import java.io.File

/**
 * 앱에 들어 있는 목표카드 묶음을 매크로의 타겟으로 옮겨 넣는다.
 *
 * 옮겨 넣은 뒤로는 **앱의 평범한 사용자 타겟과 완전히 같다.** 편집·삭제·교체가 모두
 * 된다. 프리셋은 시작점을 채워줄 뿐이고, 실행 중에 따로 읽히는 경로가 없다.
 */
class PresetImporter(
    private val context: Context,
    private val files: TemplateFiles,
) {
    /**
     * [preset] 의 카드들을 [macro] 의 타겟으로 만든다.
     *
     * 이미지 복사에 실패한 카드는 조용히 빠지지 않고 로그에 남는다. 이미지 없는 타겟을
     * 만들어 두면 매칭이 언제나 실패하는데 이유가 보이지 않기 때문이다.
     */
    fun apply(macro: Macro, preset: TargetPreset, now: Long = System.currentTimeMillis()): Macro {
        val failed = ArrayList<String>()
        val withTargets = macro.withPresetTargets(preset, now) { card ->
            copy(macro.id, "${preset.assetDir}/${card.assetFile}", card.assetFile, asTarget = true)
                .also { saved -> if (saved == null) failed += card.assetFile }
        }
        val button = preset.confirmButton
        val confirmFile = copy(
            macro.id,
            "${preset.stepAssetDir}/${button.assetFile}",
            button.assetFile,
            asTarget = false,
        )
        if (confirmFile == null) failed += button.assetFile
        if (failed.isNotEmpty()) {
            Log.e(TAG, "이미지 ${failed.size}개를 옮기지 못했습니다: ${failed.joinToString()}")
        }
        return withTargets.withPresetResultSteps(preset, now, confirmFile)
    }

    /** 실패하면 null. 부른 쪽이 그 항목을 빼고 기록한다. */
    private fun copy(
        macroId: String,
        assetPath: String,
        fileName: String,
        asTarget: Boolean,
    ): String? = try {
        val dir = if (asTarget) files.targetsDir(macroId) else files.templatesDir(macroId)
        val dest = File(dir, fileName)
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.length() > 0) fileName else null
    } catch (e: Exception) {
        Log.e(TAG, "이미지 복사 실패: $assetPath", e)
        null
    }

    private companion object {
        const val TAG = "PresetImporter"
    }
}
