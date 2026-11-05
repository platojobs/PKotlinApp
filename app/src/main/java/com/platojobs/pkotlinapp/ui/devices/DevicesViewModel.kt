package com.platojobs.pkotlinapp.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DevicesUiState(
    val all: List<Device> = emptyList(),
    val visible: List<Device> = emptyList(),
    val filter: DeviceListFilter = DeviceListFilter(),
    val groups: List<String> = emptyList(),
)

class DevicesViewModel(
    private val deviceRepository: DeviceRepository,
    private val ruleRepository: RuleRepository,
    private val ruleEventRepository: RuleEventRepository,
    private val scanLogRepository: ScanLogRepository,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val settings: AppSettings,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = deviceRepository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(
        DeviceListFilter(sort = settings.deviceSortMode),
    )
    val filter: StateFlow<DeviceListFilter> = _filter.asStateFlow()

    /** 一次组合出源列表、可见列表、分组选项与筛选状态，避免 UI 多流时序错乱。 */
    val uiState: StateFlow<DevicesUiState> =
        combine(devices, _filter) { all, query ->
            DevicesUiState(
                all = all,
                visible = DeviceListFiltering.apply(all, query),
                filter = query,
                groups = DeviceListFiltering.groupOptions(all),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    val rules: StateFlow<List<DeviceRule>> = ruleRepository.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<RuleEvent>> = ruleEventRepository.observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<BleConnectionState> = bleConnectionManager.state

    private val _trends = MutableStateFlow<Map<String, Int?>>(emptyMap())

    /** mac -> 最近两次扫描的 RSSI 差值，用真实扫描日志计算。 */
    val trends: StateFlow<Map<String, Int?>> = _trends.asStateFlow()

    init {
        viewModelScope.launch {
            scanLogRepository.observeRecent(200).collect { logs ->
                _trends.value = logs.groupBy { it.mac }.mapValues { (_, entries) ->
                    if (entries.size >= 2) entries[0].rssi - entries[1].rssi else null
                }
            }
        }
    }

    fun connect(mac: String): Boolean = bleConnectionManager.connect(mac)

    fun setSearch(text: String) {
        _filter.value = _filter.value.copy(search = text)
    }

    fun setSort(sort: DeviceSortMode) {
        if (_filter.value.sort == sort) return
        settings.deviceSortMode = sort
        _filter.value = _filter.value.copy(sort = sort)
    }

    fun setGroup(group: String?) {
        _filter.value = _filter.value.copy(group = group)
    }

    fun clearFilters() {
        _filter.value = _filter.value.copy(search = "", group = null)
    }

    fun addRule(type: RuleType, threshold: Int) {
        viewModelScope.launch {
            ruleRepository.addRule(type, threshold)
            ruleEngine.refresh()
        }
    }

    fun setRuleEnabled(rule: DeviceRule, enabled: Boolean) {
        viewModelScope.launch {
            ruleRepository.setEnabled(rule.id, enabled)
            ruleEngine.refresh()
        }
    }

    fun deleteRule(rule: DeviceRule) {
        viewModelScope.launch {
            ruleRepository.deleteRule(rule.id)
            ruleEngine.refresh()
        }
    }

    fun deleteDevice(mac: String) {
        viewModelScope.launch {
            // 软删除 + tombstone，等待同步层推送后再物理清理。
            deviceRepository.deleteDevice(mac)
            runCatching { syncScheduler.schedule() }
        }
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                val container = app.container
                DevicesViewModel(
                    deviceRepository = container.deviceRepository,
                    ruleRepository = container.ruleRepository,
                    ruleEventRepository = container.ruleEventRepository,
                    scanLogRepository = container.scanLogRepository,
                    bleConnectionManager = container.bleConnectionManager,
                    ruleEngine = container.ruleEngine,
                    syncScheduler = container.syncScheduler,
                    settings = container.settings,
                )
            }
        }
    }
}
