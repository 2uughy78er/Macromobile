package com.macromobile.inputmirror.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macromobile.inputmirror.MirrorApp
import com.macromobile.inputmirror.mirror.MirrorError
import com.macromobile.inputmirror.mirror.MirrorFailure
import com.macromobile.inputmirror.mirror.MirrorRuntime
import com.macromobile.inputmirror.mirror.MirrorState
import com.macromobile.inputmirror.mirror.ProbeResult
import com.macromobile.inputmirror.model.MirrorLayout
import com.macromobile.inputmirror.model.MirrorRegion
import com.macromobile.inputmirror.model.MirrorSettings
import com.macromobile.inputmirror.model.Region
import com.macromobile.inputmirror.model.ScreenFingerprint
import com.macromobile.inputmirror.service.MirrorAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 본 모드(여러 게임 미러링)의 화면 상태.
 *
 * 미러링 자체는 접근성 서비스 안의 엔진이 돌린다. 이 ViewModel 은 **배치를 만들고
 * 엔진에게 시키는 일**만 한다. 그래서 사용자가 게임으로 넘어가 우리 화면이 사라져도
 * 미러링은 계속된다.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val container = MirrorApp.container()

    val layout: StateFlow<MirrorLayout> = container.layoutRepository.layout
        .stateIn(viewModelScope, SharingStarted.Eagerly, MirrorLayout())

    val settings: StateFlow<MirrorSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, MirrorSettings())

    val state: StateFlow<MirrorState> = MirrorRuntime.state
    val failures = MirrorRuntime.failures
    val gestures = MirrorRuntime.gestures
    val targetResults = MirrorRuntime.targetResults
    val blockedReason = MirrorRuntime.blockedReason

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 주입 가능성 측정 결과. 비어 있으면 아직 재보지 않은 것이다. */
    private val _probeResults = MutableStateFlow<List<ProbeResult>>(emptyList())
    val probeResults: StateFlow<List<ProbeResult>> = _probeResults.asStateFlow()

    private val _probeProgress = MutableStateFlow<String?>(null)
    val probeProgress: StateFlow<String?> = _probeProgress.asStateFlow()

    /** 창 목록 덤프. 영역을 지정할 때 참고한다. */
    private val _windowDump = MutableStateFlow<String?>(null)
    val windowDump: StateFlow<String?> = _windowDump.asStateFlow()

    fun showMessage(text: String?) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    val isAccessibilityConnected: Boolean get() = MirrorAccessibilityService.isConnected

    /** 지금 화면 조건. 저장된 좌표가 아직 유효한지 보여주는 데 쓴다. */
    fun screenNow(): ScreenFingerprint =
        MirrorAccessibilityService.instance?.screenFingerprint() ?: ScreenFingerprint()

    fun layoutStatus() = layout.value.status(screenNow())

    // ------------------------------------------------------------------
    // 영역 지정
    // ------------------------------------------------------------------

    /**
     * 화면 위에 영역 지정 오버레이를 띄운다.
     *
     * 성공하면 true 를 돌려준다. 부른 쪽은 그때 우리 화면을 뒤로 보내야 한다.
     * 그래야 사용자가 진짜 게임 화면을 보면서 영역을 그릴 수 있다.
     */
    fun beginEditMaster(): Boolean {
        val service = MirrorAccessibilityService.instance ?: run {
            reportNotConnected()
            return false
        }
        val current = layout.value
        return service.showRegionEditor(
            editingName = "MASTER",
            existing = current.targets,
            initial = current.master?.bounds,
            onConfirm = { bounds -> saveMaster(bounds) },
            onCancel = { },
        )
    }

    fun beginEditTarget(target: MirrorRegion?): Boolean {
        val service = MirrorAccessibilityService.instance ?: run {
            reportNotConnected()
            return false
        }
        val current = layout.value
        val existing = buildList {
            current.master?.let { add(it) }
            addAll(current.targets.filter { it.id != target?.id })
        }
        val name = target?.name ?: MirrorLayout.defaultTargetName(current.targets)
        val id = target?.id ?: MirrorLayout.nextTargetId(current.targets)
        return service.showRegionEditor(
            editingName = name,
            existing = existing,
            initial = target?.bounds,
            onConfirm = { bounds -> saveTarget(id, name, target, bounds) },
            onCancel = { },
        )
    }

    private fun saveMaster(bounds: Region) {
        val screen = screenNow()
        viewModelScope.launch {
            container.layoutRepository.update { current ->
                current.copy(
                    master = (current.master ?: MirrorRegion(
                        id = MirrorLayout.MASTER_ID,
                        name = "MASTER",
                        bounds = bounds,
                    )).copy(bounds = bounds),
                    screen = screen,
                )
            }
            showMessage("MASTER 영역을 저장했습니다.")
        }
    }

    private fun saveTarget(
        id: String,
        name: String,
        existing: MirrorRegion?,
        bounds: Region,
    ) {
        val screen = screenNow()
        viewModelScope.launch {
            container.layoutRepository.update { current ->
                val region = existing?.copy(bounds = bounds)
                    ?: MirrorRegion(id = id, name = name, bounds = bounds)
                current.withTarget(region).copy(screen = screen)
            }
            showMessage("$name 영역을 저장했습니다.")
        }
    }

    fun removeTarget(id: String) {
        viewModelScope.launch {
            container.layoutRepository.update { it.withoutTarget(id) }
        }
    }

    fun setTargetEnabled(id: String, enabled: Boolean) = updateTarget(id) { it.copy(enabled = enabled) }

    fun setTargetDeliverInput(id: String, deliver: Boolean) =
        updateTarget(id) { it.copy(deliverInput = deliver) }

    fun setTargetDelay(id: String, delayMs: Long) =
        updateTarget(id) { it.copy(delayMs = delayMs.coerceIn(0L, 500L)) }

    private fun updateTarget(id: String, transform: (MirrorRegion) -> MirrorRegion) {
        viewModelScope.launch {
            container.layoutRepository.update { current ->
                val target = current.targets.firstOrNull { it.id == id } ?: return@update current
                current.withTarget(transform(target))
            }
            // 켜고 끈 것이 곧바로 반영되도록 엔진에도 알린다.
            MirrorAccessibilityService.instance?.engine?.applySettings(settings.value)
        }
    }

    fun clearLayout() {
        viewModelScope.launch {
            container.layoutRepository.clear()
            showMessage("영역 설정을 모두 지웠습니다.")
        }
    }

    fun refreshWindowDump() {
        val service = MirrorAccessibilityService.instance ?: run {
            reportNotConnected()
            return
        }
        _windowDump.value = service.windowDump()
    }

    // ------------------------------------------------------------------
    // START / PAUSE / STOP
    // ------------------------------------------------------------------

    fun start(): Boolean {
        val engine = MirrorAccessibilityService.instance?.engine ?: run {
            reportNotConnected()
            return false
        }
        val started = engine.start(layout.value, settings.value)
        if (!started) {
            showMessage(MirrorRuntime.blockedReason.value ?: "시작할 수 없습니다.")
        }
        return started
    }

    fun pause() {
        MirrorAccessibilityService.instance?.engine?.pause()
    }

    fun resume() {
        MirrorAccessibilityService.instance?.engine?.resume()
    }

    fun stop() {
        MirrorAccessibilityService.instance?.engine?.stop()
    }

    /**
     * 대상 여럿에 동시에 주입이 되는지 실제로 재본다.
     *
     * 이 숫자가 나오기 전에는 "3개 동시 미러링이 된다"고 말할 수 없다.
     */
    fun runInjectionProbe(repeatEach: Int = 10) {
        val engine = MirrorAccessibilityService.instance?.engine ?: run {
            reportNotConnected()
            return
        }
        viewModelScope.launch {
            _probeResults.value = emptyList()
            _probeResults.value = engine.probeInjection(repeatEach) { progress ->
                _probeProgress.value = progress
            }
            _probeProgress.value = null
        }
    }

    fun clearRecords() {
        MirrorRuntime.clearRecords()
    }

    fun gestureDump(): String = MirrorRuntime.dumpGestures()

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun reportNotConnected() {
        MirrorRuntime.addFailureExternal(MirrorFailure(MirrorError.ACCESSIBILITY_NOT_CONNECTED))
        showMessage(MirrorError.ACCESSIBILITY_NOT_CONNECTED.koreanLabel)
    }
}
