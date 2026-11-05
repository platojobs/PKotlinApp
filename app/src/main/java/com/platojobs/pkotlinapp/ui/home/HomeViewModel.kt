package com.platojobs.pkotlinapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.ble.BleError
import com.platojobs.pkotlinapp.data.ble.BleException
import com.platojobs.pkotlinapp.data.ble.BleScanner
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** 首页按分组分段的设备列表。 */
data class DeviceSection(
    val groupLabel: String?,
    val devices: List<Device>,
)

data class HomeUiState(
    val devices: List<Device> = emptyList(),
    val sections: List<DeviceSection> = emptyList(),
    val latestReadings: Map<String, Reading> = emptyMap(),
    val connectedMac: String? = null,
    val alertCountToday: Int = 0,
    val scenes: List<Scene> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val query: String = "",
    val sortMode: DeviceSortMode = DeviceSortMode.RECENT,
    val loading: Boolean = true,
    val syncError: String? = null,
)

/** 首页快捷场景的实时进度（执行中逐条推进）。 */
data class SceneProgress(
    val sceneId: Long,
    val sceneName: String,
    val index: Int,
    val total: Int,
    val action: SceneAction,
)

class HomeViewModel(
    private val deviceRepository: DeviceRepository,
    private val readingRepository: ReadingRepository,
    private val ruleEventRepository: RuleEventRepository,
    private val sceneRepository: SceneRepository,
    private val sceneExecutor: SceneExecutor,
    private val scanLogRepository: ScanLogRepository,
    private val bleScanner: BleScanner,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val syncManager: SyncManager,
    private val settings: AppSettings,
    private val unknownDeviceName: String,
) : ViewModel() {

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _ruleMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ruleMessages: SharedFlow<String> = _ruleMessages.asSharedFlow()

    private val _query = MutableStateFlow("")
    private val _sortMode = MutableStateFlow(settings.deviceSortMode)

    /** null = 全部分组；"" = 未分组；其他为真实分组名。 */
    private val _selectedGroup = MutableStateFlow<String?>(null)

    private val _loaded = MutableStateFlow(false)

    private val _sceneProgress = MutableStateFlow<SceneProgress?>(null)
    val sceneProgress: StateFlow<SceneProgress?> = _sceneProgress.asStateFlow()

    private val _sceneResults = MutableSharedFlow<SceneRunResult>(extraBufferCapacity = 4)
    val sceneResults: SharedFlow<SceneRunResult> = _sceneResults.asSharedFlow()

    private val sceneNames = mutableMapOf<Long, String>()
    private var sceneJob: Job? = null

    private val dayWindow = todayWindow()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            deviceRepository.observeDevices().onEach { _loaded.value = true },
            readingRepository.observeLatestPerDevice(),
            ruleEventRepository.observeCountBetween(dayWindow.first, dayWindow.second),
        ) { devices, readings, alerts ->
            Triple(devices, readings, alerts)
        },
        sceneRepository.observeScenes(),
        combine(_query, _sortMode, _selectedGroup) { query, sort, group ->
            Triple(query, sort, group)
        },
        bleConnectionManager.state,
        combine(_loaded, syncManager.status) { loaded, status -> loaded to status.lastError },
    ) { content, scenes, filters, connection, status ->
        val (devices, readings, alertCount) = content
        val (query, sort, group) = filters
        val (loaded, syncError) = status
        val newestPerDevice = readings.groupBy { it.deviceId }.mapValues { it.value.first() }
        HomeUiState(
            devices = devices,
            sections = sectionsOf(devices, query, group, sort),
            latestReadings = newestPerDevice,
            connectedMac = (connection as? BleConnectionState.Connected)?.mac,
            alertCountToday = alertCount,
            scenes = scenes,
            groups = devices.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct().sorted(),
            selectedGroup = group,
            query = query,
            sortMode = sort,
            loading = !loaded,
            syncError = syncError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSortMode(mode: DeviceSortMode) {
        _sortMode.value = mode
        settings.deviceSortMode = mode
    }

    fun setGroup(group: String?) {
        _selectedGroup.value = group
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

    fun syncNow() {
        syncScheduler.scheduleNow()
    }

    fun sceneName(id: Long): String =
        sceneNames[id] ?: uiState.value.scenes.firstOrNull { it.id == id }?.name.orEmpty()

    private fun sectionsOf(
        devices: List<Device>,
        query: String,
        selectedGroup: String?,
        sort: DeviceSortMode,
    ): List<DeviceSection> {
        val normalizedQuery = query.trim()
        val filtered = devices.filter { device ->
            normalizedQuery.isEmpty() ||
                device.mac.contains(normalizedQuery, ignoreCase = true) ||
                device.displayName()?.contains(normalizedQuery, ignoreCase = true) == true
        }.filter { device ->
            when (selectedGroup) {
                null -> true
                "" -> device.group.isNullOrBlank()
                else -> device.group == selectedGroup
            }
        }.sortedWith(comparatorFor(sort))

        return filtered
            .groupBy { it.group?.takeIf(String::isNotBlank) }
            .toSortedMap(compareBy<String?> { it == null }.thenBy { it.orEmpty() })
            .map { (group, items) -> DeviceSection(groupLabel = group, devices = items) }
    }

    private fun comparatorFor(sort: DeviceSortMode): Comparator<Device> = when (sort) {
        DeviceSortMode.RECENT -> compareByDescending { it.lastSeenTimestamp }
        DeviceSortMode.NAME -> compareBy { (it.displayName() ?: it.mac).lowercase() }
        DeviceSortMode.RSSI -> compareByDescending { it.lastRssi }
        DeviceSortMode.ADDED -> compareByDescending { it.createdAt }
    }

    // ---- 扫描（添加设备弹层复用） ----

    private var scanJob: Job? = null
    private val lastDatabaseWriteAt = mutableMapOf<String, Long>()
    private val loggedMacsInSession = mutableSetOf<String>()

    fun startScan() {
        if (scanJob?.isActive == true) return
        _scanResults.value = emptyList()
        lastDatabaseWriteAt.clear()
        loggedMacsInSession.clear()

        scanJob = viewModelScope.launch {
            _scanState.value = ScanState.Scanning
            runCatching { ruleEngine.refresh() }
            try {
                bleScanner.scan().collect { device -> handleScanResult(device) }
                _scanState.value = ScanState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: BleException) {
                _scanState.value = ScanState.Error(e.error, e.code)
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(BleError.SCAN_FAILED, 0)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
    }

    fun connect(mac: String): Boolean = bleConnectionManager.connect(mac)

    private fun handleScanResult(device: ScannedDevice) {
        _scanResults.update { current ->
            (current.filterNot { it.mac == device.mac } + device)
                .sortedByDescending { it.rssi }
                .take(MAX_LIVE_DEVICES)
        }

        val displayName = device.name ?: unknownDeviceName
        val now = device.timestamp
        val lastWrite = lastDatabaseWriteAt[device.mac] ?: 0L
        if (now - lastWrite >= DATABASE_WRITE_INTERVAL_MS) {
            lastDatabaseWriteAt[device.mac] = now
            viewModelScope.launch {
                deviceRepository.saveDevice(
                    Device(
                        mac = device.mac,
                        advertisedName = device.name,
                        lastRssi = device.rssi,
                        lastSeenTimestamp = now,
                    ),
                )
                runCatching { syncScheduler.schedule() }
            }
        }

        if (loggedMacsInSession.add(device.mac)) {
            viewModelScope.launch {
                scanLogRepository.add(
                    ScanLog(
                        id = 0,
                        mac = device.mac,
                        name = displayName,
                        rssi = device.rssi,
                        timestamp = now,
                    ),
                )
            }
        }

        viewModelScope.launch {
            ruleEngine.onScan(device, displayName).forEach { event ->
                _ruleMessages.tryEmit(event.message)
            }
        }
    }

    sealed interface ScanState {
        data object Idle : ScanState

        data object Scanning : ScanState

        data class Error(val error: BleError, val code: Int) : ScanState
    }

    companion object {
        private const val MAX_LIVE_DEVICES = 100
        private const val DATABASE_WRITE_INTERVAL_MS = 2_000L

        private fun todayWindow(): Pair<Long, Long> {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            return start to calendar.timeInMillis
        }

        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                val container = app.container
                HomeViewModel(
                    deviceRepository = container.deviceRepository,
                    readingRepository = container.readingRepository,
                    ruleEventRepository = container.ruleEventRepository,
                    sceneRepository = container.sceneRepository,
                    sceneExecutor = container.sceneExecutor,
                    scanLogRepository = container.scanLogRepository,
                    bleScanner = container.bleScanner,
                    bleConnectionManager = container.bleConnectionManager,
                    ruleEngine = container.ruleEngine,
                    syncScheduler = container.syncScheduler,
                    syncManager = container.syncManager,
                    settings = container.settings,
                    unknownDeviceName = app.getString(R.string.device_unknown),
                )
            }
        }
    }
}
