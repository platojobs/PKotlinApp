package com.platojobs.pkotlinapp.ui.home

import android.content.Context
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
import com.platojobs.pkotlinapp.data.household.HouseholdRepository
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.household.Household
import com.platojobs.pkotlinapp.domain.household.HouseholdErrorKind
import com.platojobs.pkotlinapp.domain.household.classifyHouseholdError
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RoomRepository
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

/** 首页按房间分段的设备列表；[room] 为 null 表示「未分配」。 */
data class DeviceSection(
    val room: Room?,
    val devices: List<Device>,
)

data class HomeUiState(
    val devices: List<Device> = emptyList(),
    val sections: List<DeviceSection> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val collapsedRooms: Set<Long> = emptySet(),
    val searching: Boolean = false,
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
    /** 可见家庭列表；多于一个时才显示切换控件。 */
    val households: List<Household> = emptyList(),
    val currentHousehold: Household? = null,
    /** 家庭接口是否同步成功；离线时房间标注「未同步」。 */
    val householdSynced: Boolean = true,
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
    private val roomRepository: RoomRepository,
    private val sceneExecutor: SceneExecutor,
    private val scanLogRepository: ScanLogRepository,
    private val bleScanner: BleScanner,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val syncManager: SyncManager,
    private val settings: AppSettings,
    private val householdRepository: HouseholdRepository,
    private val context: Context,
    private val unknownDeviceName: String,
) : ViewModel() {

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _ruleMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ruleMessages: SharedFlow<String> = _ruleMessages.asSharedFlow()

    /** 家庭操作的可读提示（403/离线等），由 Fragment 统一展示。 */
    private val _householdMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val householdMessages: SharedFlow<String> = _householdMessages.asSharedFlow()

    private val _query = MutableStateFlow("")
    private val _sortMode = MutableStateFlow(settings.deviceSortMode)

    /** null = 全部分组；"" = 未分组；其他为真实分组名。 */
    private val _selectedGroup = MutableStateFlow<String?>(null)

    /** 折叠的房间；“未分配”用 [UNASSIGNED_ROOM_ID]。 */
    private val _collapsedRooms = MutableStateFlow<Set<Long>>(emptySet())

    private val _loaded = MutableStateFlow(false)

    private val _sceneProgress = MutableStateFlow<SceneProgress?>(null)
    val sceneProgress: StateFlow<SceneProgress?> = _sceneProgress.asStateFlow()

    private val _sceneResults = MutableSharedFlow<SceneRunResult>(extraBufferCapacity = 4)
    val sceneResults: SharedFlow<SceneRunResult> = _sceneResults.asSharedFlow()

    private val sceneNames = mutableMapOf<Long, String>()
    private var sceneJob: Job? = null

    private val dayWindow = todayWindow()

    private val homeContent = combine(
        deviceRepository.observeDevices().onEach { _loaded.value = true },
        readingRepository.observeLatestPerDevice(),
        ruleEventRepository.observeCountBetween(dayWindow.first, dayWindow.second),
        roomRepository.observeRooms(),
    ) { devices, readings, alerts, rooms ->
        HomeContent(devices, readings, alerts, rooms)
    }

    private val householdContent = combine(
        householdRepository.households,
        householdRepository.current,
        householdRepository.status,
    ) { households, current, status ->
        HouseholdContent(households, current, status.synced)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(homeContent, householdContent) { content, household ->
            content to household
        },
        sceneRepository.observeScenes(),
        combine(_query, _sortMode, _selectedGroup, _collapsedRooms) { query, sort, group, collapsed ->
            HomeFilters(query, sort, group, collapsed)
        },
        bleConnectionManager.state,
        combine(_loaded, syncManager.status) { loaded, status -> loaded to status.lastError },
    ) { contentAndHousehold, scenes, filters, connection, status ->
        val (content, household) = contentAndHousehold
        val (loaded, syncError) = status
        val newestPerDevice = content.readings.groupBy { it.deviceId }.mapValues { it.value.first() }
        val currentLocalId = household.current?.localId
        val visibleRooms = content.rooms.filter { room ->
            room.householdId == null || (currentLocalId != null && room.householdId == currentLocalId)
        }
        val visibleDevices = content.devices.filter { device ->
            device.householdId == null ||
                currentLocalId == null ||
                device.householdId == currentLocalId
        }
        HomeUiState(
            devices = visibleDevices,
            sections = sectionsOf(visibleDevices, visibleRooms, filters),
            rooms = visibleRooms,
            collapsedRooms = filters.collapsed,
            searching = filters.query.isNotBlank(),
            latestReadings = newestPerDevice,
            connectedMac = (connection as? BleConnectionState.Connected)?.mac,
            alertCountToday = content.alerts,
            scenes = scenes,
            groups = visibleDevices.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct().sorted(),
            selectedGroup = filters.group,
            query = filters.query,
            sortMode = filters.sort,
            loading = !loaded,
            syncError = syncError,
            households = household.households,
            currentHousehold = household.current,
            householdSynced = household.synced,
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

    /** 折叠/展开房间；[roomId] 为 [UNASSIGNED_ROOM_ID] 表示未分配分区。 */
    fun toggleRoom(roomId: Long) {
        _collapsedRooms.update { if (roomId in it) it - roomId else it + roomId }
    }

    init {
        viewModelScope.launch { householdRepository.refreshHouseholds() }
    }

    fun createRoom(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { householdRepository.createRoom(name) }
                .onFailure(::emitHouseholdError)
        }
    }

    fun renameRoom(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { householdRepository.renameRoom(id, name) }
                .onFailure(::emitHouseholdError)
        }
    }

    fun deleteRoom(id: Long) {
        viewModelScope.launch {
            runCatching { householdRepository.deleteRoom(id) }
                .onFailure(::emitHouseholdError)
        }
    }

    /** [roomId] 为 null 时移出房间，回到「未分配」。 */
    fun assignRoom(mac: String, roomId: Long?) {
        viewModelScope.launch {
            runCatching { householdRepository.moveDeviceToRoom(mac, roomId) }
                .onFailure(::emitHouseholdError)
        }
    }

    /** 首页家庭切换：多于一个家庭时可见，切换后按该家庭拉取房间与设备。 */
    fun switchHousehold(remoteId: String) {
        viewModelScope.launch { householdRepository.switchHousehold(remoteId) }
    }

    private fun emitHouseholdError(error: Throwable) {
        _householdMessages.tryEmit(
            when (classifyHouseholdError(error)) {
                HouseholdErrorKind.PERMISSION_DENIED ->
                    context.getString(R.string.household_permission_denied)

                HouseholdErrorKind.NOT_FOUND -> context.getString(R.string.household_not_found)
                HouseholdErrorKind.INVALID_CODE -> context.getString(R.string.invite_code_invalid)
                HouseholdErrorKind.RATE_LIMITED -> context.getString(R.string.invite_rate_limited)
                HouseholdErrorKind.OFFLINE -> context.getString(R.string.household_offline_hint)
                HouseholdErrorKind.SERVER_ERROR -> context.getString(R.string.household_server_error)
            },
        )
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
        rooms: List<Room>,
        filters: HomeFilters,
    ): List<DeviceSection> {
        val normalizedQuery = filters.query.trim()
        val filtered = devices.filter { device ->
            normalizedQuery.isEmpty() ||
                device.mac.contains(normalizedQuery, ignoreCase = true) ||
                device.displayName()?.contains(normalizedQuery, ignoreCase = true) == true
        }.filter { device ->
            when (filters.group) {
                null -> true
                "" -> device.group.isNullOrBlank()
                else -> device.group == filters.group
            }
        }.sortedWith(comparatorFor(filters.sort))

        // 搜索时平铺结果，不与房间分区互相打架；清空搜索恢复房间分区。
        if (normalizedQuery.isNotEmpty()) {
            return listOf(DeviceSection(room = null, devices = filtered))
        }

        val knownRoomIds = rooms.mapTo(mutableSetOf()) { it.id }
        val byRoom = filtered.groupBy { device ->
            device.roomId?.takeIf { it in knownRoomIds }
        }
        val roomSections = rooms.map { room ->
            DeviceSection(room = room, devices = byRoom[room.id].orEmpty())
        }.filter { section -> filters.group == null || section.devices.isNotEmpty() }
        // 「未分配」固定排最后；没有未分配设备时不渲染空分区。
        val unassigned = byRoom[null].orEmpty()
        return if (unassigned.isEmpty()) roomSections
        else roomSections + DeviceSection(room = null, devices = unassigned)
    }

    private data class HomeContent(
        val devices: List<Device>,
        val readings: List<Reading>,
        val alerts: Int,
        val rooms: List<Room>,
    )

    private data class HouseholdContent(
        val households: List<Household>,
        val current: Household?,
        val synced: Boolean,
    )

    private data class HomeFilters(
        val query: String,
        val sort: DeviceSortMode,
        val group: String?,
        val collapsed: Set<Long>,
    )

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

        /** 未分配分区在折叠集合中的哨兵 id。 */
        const val UNASSIGNED_ROOM_ID = -1L

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
                    roomRepository = container.roomRepository,
                    sceneExecutor = container.sceneExecutor,
                    scanLogRepository = container.scanLogRepository,
                    bleScanner = container.bleScanner,
                    bleConnectionManager = container.bleConnectionManager,
                    ruleEngine = container.ruleEngine,
                    syncScheduler = container.syncScheduler,
                    syncManager = container.syncManager,
                    settings = container.settings,
                    householdRepository = container.householdRepository,
                    context = app.applicationContext,
                    unknownDeviceName = app.getString(R.string.device_unknown),
                )
            }
        }
    }
}
