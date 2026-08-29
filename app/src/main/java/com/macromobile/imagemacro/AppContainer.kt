package com.macromobile.imagemacro

import android.content.Context
import com.macromobile.imagemacro.input.GestureController
import com.macromobile.imagemacro.input.KeyEventController
import com.macromobile.imagemacro.input.TextInputController
import com.macromobile.imagemacro.ocr.MlKitOcrEngine
import com.macromobile.imagemacro.ocr.OcrEngine
import com.macromobile.imagemacro.storage.MacroRepository
import com.macromobile.imagemacro.storage.SettingsRepository
import com.macromobile.imagemacro.storage.TemplateFiles
import com.macromobile.imagemacro.vision.TargetDetector
import com.macromobile.imagemacro.vision.TemplateCache
import com.macromobile.imagemacro.vision.TemplateMatcher

/**
 * 앱 전역에서 한 번만 만들어 쓰는 객체들.
 *
 * 화면(Activity)과 서비스가 같은 저장소·같은 캐시를 봐야 하므로 여기에 모아둔다.
 * DI 프레임워크를 쓰지 않아 빌드가 단순하고 의존 관계가 눈에 보인다.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val files = TemplateFiles(appContext)
    val macroRepository = MacroRepository(files)
    val settingsRepository = SettingsRepository(appContext)

    val templateCache = TemplateCache()
    val templateMatcher = TemplateMatcher(templateCache)
    val detector = TargetDetector(templateMatcher, files)

    val gestures = GestureController()
    val keyEvents = KeyEventController(gestures)
    val textInput = TextInputController(appContext)

    val ocr: OcrEngine by lazy { MlKitOcrEngine() }

    /** 템플릿 이미지가 바뀌면 캐시를 비워 다음 매칭에서 새로 읽게 한다. */
    fun invalidateImageCache() = templateCache.invalidate()
}
