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
import com.platojobs.pkotlinapp.domain.reading.ReadingRange
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.reading.ReadingSeries
import com.platojobs.pkotlinapp.domain.reading.ReadingSeriesBucketing
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import kotlinx.coroutines.Job
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

/** 指标 Chip 的展示项：指标名 · N 条；unit 为响应中最新一条读数的单位。 */
data class MetricItem(
    val metric: String,
    val unit: String,
    val count: Long,
    val lastAt: Long,
)

/**
 * 详情页「数据」区状态。
 * 指标与长周期序列以服务端真值为准；网络失败时回退本地（本地仅 7 天）。
 */
data class ReadingsState(
    val metrics: List<MetricItem> = emptyList(),
    val selectedMetric: String? = null,
    val selectedRange: ReadingRange = ReadingRange.DEFAULT,
    val series: ReadingSeries? = null,
    val summary: ReadingSummary? = null,
    val recent: List<Reading> = emptyList(),
    val loadingMetrics: Boolean = true,
    val loadingSeries: Boolean = false,
    val offlineLocal: Boolean = false,
    /** 30 天区间离线时只有本地 7 天数据。 */
    val offlineOnlyLocal7d: Boolean = false,
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
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = bleConnectionManager.state
    val rssi: StateFlow<Int?> = bleConnectionManager.rssi

    private val _info = MutableStateFlow<InfoState>(InfoState.Idle)
    val info: StateFlow<InfoState> = _info.asStateFlow()

    private val _encryption = MutableStateFlow<EncryptionInfo?>(null)
    val encryption: StateFlow<EncryptionInfo?> = _encryption.asStateFlow()

    private val _readings = MutableStateFlow(ReadingsState())
    val readings: StateFlow<ReadingsState> = _readings.asStateFlow()

    private var metricsJob: Job? = null
    private var seriesJob: Job? = null

    init {
        viewModelScope.launch { ruleEngine.refresh() }
        observeReadings()
        refreshMetrics()

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
        if (_readings.value.selectedMetric == metric) return
        _readings.update { it.copy(selectedMetric = metric) }
        reloadSeries()
        viewModelScope.launch { refreshSummary(metric) }
    }

    fun selectRange(range: ReadingRange) {
        if (_readings.value.selectedRange == range) return
        _readings.update { it.copy(selectedRange = range) }
        reloadSeries()
    }

    /**
     * 指标清单来自服务端 `/readings/metrics`（按 lastAt 倒序）；
     * 仅当网络失败时使用本地聚合兜底。本地指标名变化时重新拉取，保证新指标能出现。
     */
    private fun observeReadings() {
        viewModelScope.launch {
            readingRepository.observeMetrics(mac)
                .map { it.sorted() }
                .distinctUntilChanged()
                .collect {
                    delay(METRICS_DEBOUNCE_MS)
                    refreshMetrics()
                }
        }

        viewModelScope.launch {
            _readings.map { it.selectedMetric }
                .distinctUntilChanged()
                .collectLatest { metric ->
                    if (metric == null) {
                        _readings.update { it.copy(recent = emptyList(), summary = null) }
                        return@collectLatest
                    }
                    readingRepository.observeRecent(mac, metric, RECENT_ROWS).collectLatest { rows ->
                        // 最近记录保持最新在前；图表由服务端分桶序列提供。
                        _readings.update { it.copy(recent = rows) }
                        // 新读数连续到达时做轻微防抖，避免频繁请求 summary/series。
                        delay(REFRESH_DEBOUNCE_MS)
                        refreshSummary(metric)
                        reloadSeries()
                    }
                }
        }
    }

    private fun refreshMetrics() {
        metricsJob?.cancel()
        metricsJob = viewModelScope.launch {
            _readings.update { it.copy(loadingMetrics = true) }
            val remote = runCatching { readingRemote.metrics(mac) }.getOrNull()
            val items = if (remote != null) {
                remote.map { MetricItem(it.metric, it.unit, it.count, it.lastAt) }
            } else {
                runCatching { readingRepository.localMetrics(mac) }
                    .getOrDefault(emptyList())
                    .map { MetricItem(it.metric, it.unit, it.count, it.lastAt) }
            }
            val previous = _readings.value.selectedMetric
            val selected = previous?.takeIf { metric -> items.any { it.metric == metric } }
                ?: items.firstOrNull()?.metric
            _readings.update {
                it.copy(
                    metrics = items,
                    selectedMetric = selected,
                    loadingMetrics = false,
                )
            }
            if (selected != previous) {
                if (selected == null) {
                    seriesJob?.cancel()
                    _readings.update { it.copy(series = null, loadingSeries = false) }
                } else {
                    reloadSeries()
                }
            }
        }
    }

    /**
     * 长周期序列：优先服务端 series；失败时把本地读数按同一 UTC 网格聚合。
     * 30 天区间离线时本地只有 7 天，标记 [ReadingsState.offlineOnlyLocal7d]。
     */
    private fun reloadSeries() {
        val metric = _readings.value.selectedMetric ?: return
        val range = _readings.value.selectedRange
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            val to = now()
            val from = to - range.windowMillis
            _readings.update { it.copy(loadingSeries = true, offlineOnlyLocal7d = false) }
            val remote = runCatching {
                readingRemote.series(mac, metric, from, to, range.effectiveBucket())
            }.getOrNull()
            if (remote != null) {
                _readings.update {
                    it.copy(
                        series = remote,
                        loadingSeries = false,
                        offlineLocal = false,
                        offlineOnlyLocal7d = false,
                    )
                }
                return@launch
            }

            val local = runCatching { readingRepository.readingsSince(mac, metric, from) }
                .getOrDefault(emptyList())
            val buckets = ReadingSeriesBucketing.aggregate(local, range.effectiveBucket())
            val unit = local.lastOrNull()?.unit?.takeIf { it.isNotBlank() }
                ?: ReadingMetrics.unit(metric)
            _readings.update {
                it.copy(
                    series = ReadingSeries(
                        metric = metric,
                        unit = unit,
                        bucket = range.effectiveBucket(),
                        from = from,
                        to = to,
                        buckets = buckets,
                    ),
                    loadingSeries = false,
                    offlineLocal = true,
                    offlineOnlyLocal7d = range == ReadingRange.DAY_30,
                )
            }
        }
    }

    /** 汇总固定 24 小时，不随区间切换；单位取该指标最新读数。 */
    private suspend fun refreshSummary(metric: String) {
        val local = runCatching { readingRepository.summary(mac, metric, 24) }
            .getOrNull() ?: return
        val remote = runCatching { readingRemote.remoteSummary(mac, metric, 24) }.getOrNull()
        // 远端为空但本地有未同步数据时不覆盖本地。
        val summary = remote?.takeIf { it.count > 0 } ?: local
        _readings.update {
            it.copy(summary = summary)
        }
    }

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
        private const val RECENT_ROWS = 10
        private const val REFRESH_DEBOUNCE_MS = 1_000L
        private const val METRICS_DEBOUNCE_MS = 600L

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
