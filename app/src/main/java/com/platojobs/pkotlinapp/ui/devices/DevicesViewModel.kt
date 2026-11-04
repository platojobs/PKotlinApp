package com.platojobs.pkotlinapp.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val deviceRepository: DeviceRepository,
    private val ruleRepository: RuleRepository,
    private val ruleEventRepository: RuleEventRepository,
    private val scanLogRepository: ScanLogRepository,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = deviceRepository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                )
            }
        }
    }
}
