package com.platojobs.pkotlinapp.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.toOfflineDeviceInfo
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EncryptionInfo(
    val enabled: Boolean,
    val timestamp: Long,
)

/** 详情页「数据」区状态；列表/图表只来自本地 Room，汇总可优先用服务端。 */
data class ReadingsState(
    val metrics: List<String> = emptyList(),
    val selectedMetric: String? = null,
    val points: List<Reading> = emptyList(),
    val summary: ReadingSummary? = null,
    val recent: List<Reading> = emptyList(),
    val offlineLocal: Boolean = false,
    val loading: Boolean = true,
)

class DeviceDetailViewModel(
    private val mac: String,
    private val deviceName: String,
    private val bleConnectionManager: BleConnectionManager,
    private val deviceInfoRepository: DeviceInfoRepository,
    private val deviceRepository: DeviceRepository,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val readingRepository: ReadingRepository,
    private val readingRemote: ReadingRemote,
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = bleConnectionManager.state
    val rssi: StateFlow<Int?> = bleConnectionManager.rssi

    private val _info = MutableStateFlow<InfoState>(InfoState.Idle)
    val info: StateFlow<InfoState> = _info.asStateFlow()

    private val _encryption = MutableStateFlow<EncryptionInfo?>(null)
    val encryption: StateFlow<EncryptionInfo?> = _encryption.asStateFlow()

    private val _readings = MutableStateFlow(ReadingsState())
    val readings: StateFlow<ReadingsState> = _readings.asStateFlow()

    init {
        viewModelScope.launch { ruleEngine.refresh() }
        observeReadings()

        viewModelScope.launch {
            connectionState.collectLatest { state ->
                if (state is BleConnectionState.Connected && state.mac == mac) {
                    if (_info.value !is InfoState.Success) {
                        loadInfo()
                    }
                    // 连接期间周期性读取远端 RSSI，驱动自绘信号视图。
                    while (true) {
                        delay(RSSI_POLL_INTERVAL_MS)
                        bleConnectionManager.readRssi()
                    }
                }
            }
        }

        viewModelScope.launch {
            var wasConnected = false
            connectionState.collect { state ->
                val connected = state is BleConnectionState.Connected && state.mac == mac
                if (wasConnected && !connected && state is BleConnectionState.Disconnected) {
                    ruleEngine.refresh()
                    ruleEngine.onDisconnected(mac, deviceName)
                }
                wasConnected = connected
            }
        }
    }

    fun selectMetric(metric: String) {
        _readings.update {
            if (it.selectedMetric == metric) it else it.copy(selectedMetric = metric)
        }
    }

    /**
     * 指标 Chip 只来自该设备真实出现过的读数；
     * 图表/最近记录订阅本地 Flow，汇总优先远端、失败回退本地。
     */
    private fun observeReadings() {
        viewModelScope.launch {
            readingRepository.observeMetrics(mac).collect { metrics ->
                val ordered = metrics.sortedBy { metricOrder(it) }
                val selected = _readings.value.selectedMetric
                    ?.takeIf { it in ordered }
                    ?: ordered.firstOrNull()
                _readings.update {
                    it.copy(
                        metrics = ordered,
                        selectedMetric = selected,
                        loading = false,
                    )
                }
            }
        }

        viewModelScope.launch {
            _readings.map { it.selectedMetric }
                .distinctUntilChanged()
                .collectLatest { metric ->
                    if (metric == null) {
                        _readings.update {
                            it.copy(points = emptyList(), recent = emptyList(), summary = null)
                        }
                        return@collectLatest
                    }
                    readingRepository.observeRecent(mac, metric, CHART_POINTS).collectLatest { rows ->
                        // DAO 按时间倒序返回：图表需要正序，最近记录保持最新在前。
                        _readings.update {
                            it.copy(
                                points = rows.asReversed(),
                                recent = rows.take(RECENT_ROWS),
                            )
                        }
                        // 新读数连续到达时做轻微防抖，避免频繁请求 summary。
                        delay(SUMMARY_DEBOUNCE_MS)
                        refreshSummary(metric)
                    }
                }
        }
    }

    private suspend fun refreshSummary(metric: String) {
        val local = runCatching { readingRepository.summary(mac, metric, 24) }
            .getOrNull() ?: return
        val remote = runCatching { readingRemote.remoteSummary(mac, metric, 24) }.getOrNull()
        // 远端为空但本地有未同步数据时不覆盖本地；远端失败则标注离线本地数据。
        val summary = remote?.takeIf { it.count > 0 } ?: local
        _readings.update {
            it.copy(summary = summary, offlineLocal = remote == null)
        }
    }

    private fun metricOrder(metric: String): Int =
        ReadingMetrics.ALL.indexOf(metric).let { if (it < 0) ReadingMetrics.ALL.size else it }

    fun connect(): Boolean = bleConnectionManager.connect(mac)

    fun disconnect() {
        bleConnectionManager.disconnect(mac)
    }

    fun retryAfterRepair(): Boolean = bleConnectionManager.retryAfterRepair(mac)

    fun loadInfo() {
        if (_info.value is InfoState.Loading) return
        viewModelScope.launch {
            _info.value = InfoState.Loading
            val remote = runCatching { deviceInfoRepository.fetchDeviceInfo(mac) }
            _info.value = remote.fold(
                onSuccess = { InfoState.Success(it) },
                onFailure = { error ->
                    // 云端 enrich 失败时只用本地缓存降级，不覆盖、不伪造字段。
                    val local = runCatching { deviceRepository.getDevice(mac) }.getOrNull()
                    if (local == null) {
                        InfoState.Error(error.message)
                    } else {
                        InfoState.Success(local.toOfflineDeviceInfo())
                    }
                },
            )
        }
    }

    fun onKeyMissing() {
        bleConnectionManager.markKeyMissing(mac)
        viewModelScope.launch {
            ruleEngine.refresh()
            ruleEngine.onKeyMissing(mac, deviceName)
        }
    }

    fun onEncryptionChanged(enabled: Boolean) {
        _encryption.value = EncryptionInfo(enabled, System.currentTimeMillis())
    }

    /** 设备编辑：别名与分组直接落 Room，并排队同步；离线时也能立即可见。 */
    fun saveProfile(alias: String, group: String?) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = deviceRepository.getDevice(mac)
            // 只有用户真的改动了别名才置脏；仅改分组时 alias 保持 absent，避免误清空。
            // 本地别名为空时以详情页拿到的云端别名作为初始值。
            val previousAlias = existing?.name?.takeIf { it.isNotBlank() }
                ?: (_info.value as? InfoState.Success)?.info?.alias.orEmpty()
            val aliasChanged = alias != previousAlias
            val device = existing?.copy(
                name = alias,
                aliasDirty = existing.aliasDirty || aliasChanged,
                group = group,
                updatedAt = now,
            ) ?: Device(
                mac = mac,
                name = alias,
                aliasDirty = aliasChanged,
                lastRssi = 0,
                lastSeenTimestamp = now,
                group = group,
            )
            deviceRepository.saveDevice(device)
            (_info.value as? InfoState.Success)?.let { state ->
                _info.value = InfoState.Success(
                    state.info.copy(alias = alias, group = group.orEmpty()),
                )
            }
            runCatching { syncScheduler.schedule() }
        }
    }

    sealed interface InfoState {
        data object Idle : InfoState

        data object Loading : InfoState

        data class Success(val info: DeviceInfo) : InfoState

        data class Error(val message: String?) : InfoState
    }

    companion object {
        private const val RSSI_POLL_INTERVAL_MS = 2_000L
        private const val CHART_POINTS = 60
        private const val RECENT_ROWS = 10
        private const val SUMMARY_DEBOUNCE_MS = 500L

        fun factory(
            app: PKotlinApplication,
            mac: String,
            name: String,
        ) = viewModelFactory {
            initializer {
                val container = app.container
                DeviceDetailViewModel(
                    mac = mac,
                    deviceName = name,
                    bleConnectionManager = container.bleConnectionManager,
                    deviceInfoRepository = container.deviceInfoRepository,
                    deviceRepository = container.deviceRepository,
                    ruleEngine = container.ruleEngine,
                    syncScheduler = container.syncScheduler,
                    readingRepository = container.readingRepository,
                    readingRemote = container.readingRemote,
                )
            }
        }
    }
}
