package com.platojobs.pkotlinapp.ui.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.ble.RssiReadState
import com.platojobs.pkotlinapp.data.collection.CollectionSession
import com.platojobs.pkotlinapp.data.collection.CollectionStatus
import com.platojobs.pkotlinapp.data.household.HouseholdRepository
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.household.HouseholdErrorKind
import com.platojobs.pkotlinapp.domain.household.classifyHouseholdError
import com.platojobs.pkotlinapp.domain.export.ExportTooManyRowsException
import com.platojobs.pkotlinapp.domain.export.ReadingCsv
import com.platojobs.pkotlinapp.domain.export.ReadingCsvRow
import com.platojobs.pkotlinapp.domain.export.ReadingPager
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.Room
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
import com.platojobs.pkotlinapp.domain.repository.RoomRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/** 导出状态：进度条使用真实条数，失败保留原因供重试。 */
sealed interface ExportState {
    data object Idle : ExportState

    data class Running(val rows: Int) : ExportState

    data class Ready(val file: File, val rows: Int) : ExportState

    enum class ErrorKind { NO_METRIC, TOO_MANY, FAILED }

    data class Error(val kind: ErrorKind, val detail: String = "") : ExportState
}

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
    private val exportDir: File,
    private val settings: AppSettings,
    private val collectionSession: CollectionSession,
    private val roomRepository: RoomRepository,
    private val householdRepository: HouseholdRepository,
    private val context: Application,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** 当前家庭下的房间；服务端房间合并后自动更新。 */
    val rooms: StateFlow<List<Room>> = combine(
        roomRepository.observeRooms(),
        householdRepository.current,
    ) { rooms, current ->
        val currentId = current?.localId
        rooms.filter { it.householdId == null || (currentId != null && it.householdId == currentId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val roomId: StateFlow<Long?> = deviceRepository.observeDevice(mac)
        .map { it?.roomId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _roomMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val roomMessages: SharedFlow<String> = _roomMessages.asSharedFlow()

    fun assignRoom(targetRoomId: Long?) {
        viewModelScope.launch {
            if (deviceRepository.observeDevice(mac).first()?.shared == true) {
                _roomMessages.tryEmit(context.getString(R.string.household_shared_device_readonly))
                return@launch
            }
            runCatching { householdRepository.moveDeviceToRoom(mac, targetRoomId) }
                .onFailure(::emitRoomError)
        }
    }

    /** 详情页尚无房间时的新建入口：建好后直接把当前设备放进去。 */
    fun createRoomAndAssign(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            if (deviceRepository.observeDevice(mac).first()?.shared == true) {
                _roomMessages.tryEmit(context.getString(R.string.household_shared_device_readonly))
                return@launch
            }
            runCatching {
                val id = householdRepository.createRoom(name)
                householdRepository.moveDeviceToRoom(mac, id)
            }.onFailure(::emitRoomError)
        }
    }

    private fun emitRoomError(error: Throwable) {
        _roomMessages.tryEmit(
            when (classifyHouseholdError(error)) {
                HouseholdErrorKind.PERMISSION_DENIED ->
                    context.getString(R.string.household_permission_denied)

                HouseholdErrorKind.NOT_FOUND -> context.getString(R.string.household_not_found)
                HouseholdErrorKind.INVALID_CODE -> context.getString(R.string.invite_code_invalid)
                HouseholdErrorKind.RATE_LIMITED -> context.getString(R.string.invite_rate_limited)
                HouseholdErrorKind.CONFLICT -> context.getString(R.string.household_conflict)
                HouseholdErrorKind.OFFLINE -> context.getString(R.string.household_offline_hint)
                HouseholdErrorKind.SERVER_ERROR -> context.getString(R.string.household_server_error)
            },
        )
    }

    val connectionState: StateFlow<BleConnectionState> = bleConnectionManager.state
    val rssi: StateFlow<Int?> = bleConnectionManager.rssi

    /** RSSI 读数时间，与 [rssi] 同步；未知或断开时为 null。 */
    val rssiUpdatedAt: StateFlow<Long?> = bleConnectionManager.rssiUpdatedAt

    /** 最近一次 RSSI 读取结果；STALE 时展示的仍是上次成功值与时间。 */
    val rssiReadState: StateFlow<RssiReadState> = bleConnectionManager.rssiReadState

    /** 后台采集开关（持久化）；采集状态来自全局会话。 */
    private val _backgroundCollection = MutableStateFlow(settings.backgroundCollectionEnabled)
    val backgroundCollection: StateFlow<Boolean> = _backgroundCollection.asStateFlow()
    val collectionStatus: StateFlow<CollectionStatus> = collectionSession.status

    /** 记录用户选择；真正启停前台服务由 Activity 在连接态下执行。 */
    fun setBackgroundCollectionEnabled(enabled: Boolean) {
        settings.backgroundCollectionEnabled = enabled
        _backgroundCollection.value = enabled
    }

    private val _info = MutableStateFlow<InfoState>(InfoState.Idle)
    val info: StateFlow<InfoState> = _info.asStateFlow()

    private val _encryption = MutableStateFlow<EncryptionInfo?>(null)
    val encryption: StateFlow<EncryptionInfo?> = _encryption.asStateFlow()

    private val _readings = MutableStateFlow(ReadingsState())
    val readings: StateFlow<ReadingsState> = _readings.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private var metricsJob: Job? = null
    private var seriesJob: Job? = null
    private var rssiPollJob: Job? = null
    private var pageVisible = false

    init {
        viewModelScope.launch { ruleEngine.refresh() }
        observeReadings()
        refreshMetrics()
        // 云端/跨平台设备没有本机 BLE 连接，不能只在连上后才加载信息，
        // 否则「云端状态」与最近上报在详情页永远看不到。
        loadInfo()

        viewModelScope.launch {
            connectionState.collectLatest { state ->
                if (state is BleConnectionState.Connected && state.mac == mac) {
                    if (_info.value !is InfoState.Success) {
                        loadInfo()
                    }
                }
                // 连接/断开都重新评估轮询：只有可见且已连接才读 RSSI。
                syncRssiPolling()
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

    /**
     * 页面可见性变化（onStart/onStop、前后台切换）：不可见即停表，回到前台且仍连接再起。
     * 停表只取消采集，不清空 [rssiUpdatedAt]，否则回到页面读数时间会消失。
     */
    fun setPageVisible(visible: Boolean) {
        if (pageVisible == visible) return
        pageVisible = visible
        syncRssiPolling()
    }

    private fun syncRssiPolling() {
        val connected = (connectionState.value as? BleConnectionState.Connected)?.mac == mac
        if (pageVisible && connected) {
            if (rssiPollJob?.isActive == true) return
            rssiPollJob = viewModelScope.launch {
                while (isActive) {
                    bleConnectionManager.readRssi()
                    delay(RSSI_POLL_INTERVAL_MS)
                }
            }
        } else {
            rssiPollJob?.cancel()
            rssiPollJob = null
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
            val bucket = range.effectiveBucket()
            _readings.update { it.copy(loadingSeries = true, offlineOnlyLocal7d = false) }
            val remote = runCatching { remoteSeries(metric, from, to, bucket) }.getOrNull()
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
            val buckets = ReadingSeriesBucketing.aggregate(local, bucket)
            val unit = local.lastOrNull()?.unit?.takeIf { it.isNotBlank() }
                ?: ReadingMetrics.unit(metric)
            _readings.update {
                it.copy(
                    series = ReadingSeries(
                        metric = metric,
                        unit = unit,
                        bucket = bucket,
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

    /**
     * 30 分钟桶不在服务端 series 白名单（1m/1h/1d）内：
     * 24 小时视图按原读数分页拉取后在客户端聚合成 48 个桶，其余区间直接用服务端分桶。
     */
    private suspend fun remoteSeries(
        metric: String,
        from: Long,
        to: Long,
        bucket: String,
    ): ReadingSeries {
        if (bucket == ReadingRange.BUCKET_30_MIN) {
            val rows = ReadingPager { limit, cursor ->
                readingRemote.queryReadings(mac, metric, from, to, limit, cursor)
            }.fetchAll()
            val unit = rows.lastOrNull()?.unit?.takeIf { it.isNotBlank() }
                ?: ReadingMetrics.unit(metric)
            return ReadingSeries(
                metric = metric,
                unit = unit,
                bucket = bucket,
                from = from,
                to = to,
                buckets = ReadingSeriesBucketing.aggregateRemote(rows, bucket),
            )
        }
        return readingRemote.series(mac, metric, from, to, bucket)
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

    /**
     * 导出当前设备 + 当前指标 + 当前区间：
     * - 7 天内直接读 Room；超过 7 天按 cursor 分页拉服务端，最多 5 万条；
     * - 文件只写 cacheDir/exports；进度使用真实条数。
     */
    fun exportReadings() {
        if (_export.value is ExportState.Running) return
        val current = _readings.value
        val metric = current.selectedMetric
        if (metric == null) {
            _export.value = ExportState.Error(ExportState.ErrorKind.NO_METRIC)
            return
        }
        val range = current.selectedRange
        val to = now()
        val from = to - range.windowMillis
        val fallbackUnit = current.metrics.firstOrNull { it.metric == metric }?.unit
            ?: ReadingMetrics.unit(metric)

        _export.value = ExportState.Running(0)
        viewModelScope.launch {
            try {
                val rows = if (range.windowMillis <= Reading.LOCAL_RETENTION_MILLIS) {
                    readingRepository.readingsBetween(mac, metric, from, to).map { reading ->
                        ReadingCsvRow(
                            recordedAt = reading.recordedAt,
                            metric = reading.metric,
                            value = reading.value,
                            unit = reading.unit.ifBlank { fallbackUnit },
                        )
                    }
                } else {
                    val remote = ReadingPager { limit, cursor ->
                        readingRemote.queryReadings(mac, metric, from, to, limit, cursor)
                    }.fetchAll { count ->
                        _export.value = ExportState.Running(count)
                    }
                    remote.sortedBy { it.recordedAt }.map { reading ->
                        ReadingCsvRow(
                            recordedAt = reading.recordedAt,
                            metric = reading.metric.ifBlank { metric },
                            value = reading.value,
                            unit = reading.unit.ifBlank { fallbackUnit },
                        )
                    }
                }

                _export.value = ExportState.Running(rows.size)
                val file = withContext(Dispatchers.IO) {
                    exportDir.mkdirs()
                    val target = File(
                        exportDir,
                        ReadingCsv.fileName(mac, metric, "${range.hours}h", now()),
                    )
                    target.writeText(ReadingCsv.render(rows), Charsets.UTF_8)
                    target
                }
                _export.value = ExportState.Ready(file, rows.size)
            } catch (e: ExportTooManyRowsException) {
                _export.value = ExportState.Error(
                    ExportState.ErrorKind.TOO_MANY,
                    e.maxRows.toString(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _export.value = ExportState.Error(
                    ExportState.ErrorKind.FAILED,
                    e.message.orEmpty(),
                )
            }
        }
    }

    /** UI 已分享/已提示错误后回到 Idle，避免配置变化时重复弹分享。 */
    fun consumeExport() {
        if (_export.value !is ExportState.Running) {
            _export.value = ExportState.Idle
        }
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
                    exportDir = File(app.cacheDir, "exports"),
                    settings = container.settings,
                    collectionSession = container.collectionSession,
                    roomRepository = container.roomRepository,
                    householdRepository = container.householdRepository,
                    context = app,
                )
            }
        }
    }
}
