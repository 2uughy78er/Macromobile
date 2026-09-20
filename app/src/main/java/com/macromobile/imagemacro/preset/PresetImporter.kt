package com.macromobile.imagemacro.preset

import android.content.Context
import android.util.Log
import com.macromobile.imagemacro.model.Macro
import com.macromobile.imagemacro.storage.TemplateFiles
import java.io.File

/**
 * 앱에 들어 있는 리세 묶음(목표카드 + 단계 버튼 이미지)을 매크로로 옮겨 넣는다.
 *
 * 옮겨 넣은 뒤로는 **앱의 평범한 사용자 타겟·단계와 완전히 같다.** 편집·삭제·교체가
 * 모두 된다. 프리셋은 시작점을 채워줄 뿐이고, 실행 중에 따로 읽히는 경로가 없다.
 */
class PresetImporter(
    private val context: Context,
    private val files: TemplateFiles,
) {
    /**
     * [preset] 의 카드들을 [macro] 의 타겟으로 만들고, 리세 한 바퀴를 단계로 채운다.
     *
     * 이미지 복사에 실패한 항목은 조용히 빠지지 않고 로그에 남는다. 이미지 없는 타겟이나
     * 단계를 만들어 두면 언제나 실패하는데 이유가 보이지 않기 때문이다.
     */
    fun apply(macro: Macro, preset: TargetPreset, now: Long = System.currentTimeMillis()): Macro {
        val failed = ArrayList<String>()
        val withTargets = macro.withPresetTargets(preset, now) { card ->
            copy("${preset.assetDir}/${card.assetFile}", files.targetsDir(macro.id), card.assetFile)
                .also { if (it == null) failed += card.assetFile }
        }
        // 같은 그림을 여러 단계가 쓸 수 있어서 한 번만 옮긴다.
        val copied = HashMap<String, String?>()
        val withSteps = withTargets.withRerollFlowSteps(preset, now) { step ->
            copied.getOrPut(step.assetFile) {
                copy(
                    "${preset.stepAssetDir}/${step.assetFile}",
                    files.templatesDir(macro.id),
                    step.assetFile,
                ).also { if (it == null) failed += step.assetFile }
            }
        }
        if (failed.isNotEmpty()) {
            Log.e(TAG, "이미지 ${failed.size}개를 옮기지 못했습니다: ${failed.joinToString()}")
        }
        return withSteps
    }

    /** 실패하면 null. 부른 쪽이 그 항목을 빼고 기록한다. */
    private fun copy(assetPath: String, dir: File, fileName: String): String? = try {
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
