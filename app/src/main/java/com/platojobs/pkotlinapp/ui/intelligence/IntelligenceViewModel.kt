package com.platojobs.pkotlinapp.ui.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
import com.platojobs.pkotlinapp.ui.home.SceneProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IntelligenceUiState(
    val rules: List<DeviceRule> = emptyList(),
    val events: List<RuleEvent> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val devices: List<Device> = emptyList(),
    val loading: Boolean = true,
    val loadError: String? = null,
)

class IntelligenceViewModel(
    private val ruleRepository: RuleRepository,
    private val ruleEventRepository: RuleEventRepository,
    private val sceneRepository: SceneRepository,
    private val sceneExecutor: SceneExecutor,
    private val ruleEngine: RuleEngine,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    private val _loaded = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _sceneProgress = MutableStateFlow<SceneProgress?>(null)
    val sceneProgress: StateFlow<SceneProgress?> = _sceneProgress.asStateFlow()

    private val _sceneResults = MutableSharedFlow<SceneRunResult>(extraBufferCapacity = 4)
    val sceneResults: SharedFlow<SceneRunResult> = _sceneResults.asSharedFlow()

    private val sceneNames = mutableMapOf<Long, String>()
    private var sceneJob: Job? = null

    val uiState: StateFlow<IntelligenceUiState> = combine(
        ruleRepository.observeRules().onEach { _loaded.value = true },
        ruleEventRepository.observeRecent(EVENT_LIMIT),
        sceneRepository.observeScenes(),
        deviceRepository.observeDevices(),
        _loadError,
    ) { rules, events, scenes, devices, error ->
        IntelligenceUiState(
            rules = rules,
            events = events,
            scenes = scenes,
            devices = devices,
            loading = !_loaded.value,
            loadError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntelligenceUiState(),
    )

    init {
        refresh()
    }

    /** 错误块的「重试」：重新读一次本地数据，失败时保留真实错误文案。 */
    fun refresh() {
        viewModelScope.launch {
            runCatching { sceneRepository.getScenes() }
                .onSuccess { _loadError.value = null }
                .onFailure {
                    _loadError.value = it.message?.takeIf(String::isNotBlank)
                        ?: "本地场景读取失败"
                }
        }
    }

    fun addRule(type: RuleType, threshold: Int) {
        viewModelScope.launch {
            runCatching { ruleRepository.addRule(type, threshold) }
                .onSuccess { ruleEngine.refresh() }
                .onFailure { _messages.tryEmit(it.message ?: "规则保存失败") }
        }
    }

    fun setRuleEnabled(rule: DeviceRule, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { ruleRepository.setEnabled(rule.id, enabled) }
                .onSuccess { ruleEngine.refresh() }
                .onFailure { _messages.tryEmit(it.message ?: "规则状态更新失败") }
        }
    }

    fun deleteRule(rule: DeviceRule) {
        viewModelScope.launch {
            runCatching { ruleRepository.deleteRule(rule.id) }
                .onSuccess { ruleEngine.refresh() }
                .onFailure { _messages.tryEmit(it.message ?: "规则删除失败") }
        }
    }

    fun saveScene(id: Long?, name: String, actions: List<SceneAction>) {
        viewModelScope.launch {
            runCatching { sceneRepository.saveScene(id, name, actions) }
                .onSuccess { _messages.tryEmit("场景已保存") }
                .onFailure { _messages.tryEmit(it.message ?: "场景保存失败") }
        }
    }

    fun deleteScene(scene: Scene) {
        viewModelScope.launch {
            runCatching { sceneRepository.deleteScene(scene.id) }
                .onSuccess { _messages.tryEmit("已删除场景「${scene.name}」") }
                .onFailure { _messages.tryEmit(it.message ?: "场景删除失败") }
        }
    }

    fun runScene(scene: Scene) {
        if (sceneJob?.isActive == true) return
        if (scene.actions.isEmpty()) return
        sceneNames[scene.id] = scene.name
        sceneJob = viewModelScope.launch {
            val result = sceneExecutor.run(scene) { index, total, action ->
                _sceneProgress.value = SceneProgress(scene.id, scene.name, index, total, action)
            }
            _sceneProgress.value = null
            _sceneResults.tryEmit(result)
        }
    }

    fun sceneName(id: Long): String =
        sceneNames[id] ?: uiState.value.scenes.firstOrNull { it.id == id }?.name.orEmpty()

    companion object {
        private const val EVENT_LIMIT = 20

        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                val container = app.container
                IntelligenceViewModel(
                    ruleRepository = container.ruleRepository,
                    ruleEventRepository = container.ruleEventRepository,
                    sceneRepository = container.sceneRepository,
                    sceneExecutor = container.sceneExecutor,
                    ruleEngine = container.ruleEngine,
                    deviceRepository = container.deviceRepository,
                )
            }
        }
    }
}
