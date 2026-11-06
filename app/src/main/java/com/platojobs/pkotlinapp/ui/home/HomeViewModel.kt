package com.platojobs.pkotlinapp.ui.home

import android.app.Application
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
import com.platojobs.pkotlinapp.data.scene.SceneTriggerRunner
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.household.Household
import com.platojobs.pkotlinapp.domain.household.HouseholdErrorKind
import com.platojobs.pkotlinapp.domain.household.RoomSetMismatchException
import com.platojobs.pkotlinapp.domain.household.classifyHouseholdError
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.HourCount
import com.platojobs.pkotlinapp.domain.model.MetricCount
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RoomRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRunRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

/** 首页设备状态下钻的筛选维度。 */
enum class HomeStatusFilter {
    ONLINE,
    OFFLINE,
    UNASSIGNED,
    LOW_BATTERY,
}

/** 首页聚合：全部来自 readings / rule_events / scene_runs 与设备表的真实数据。 */
data class HomeOverview(
    val todayReadings: Int = 0,
    val metricCounts: List<MetricCount> = emptyList(),
    val hourlyCounts: List<HourCount> = emptyList(),
    val violationCount: Int = 0,
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
    val unassignedCount: Int = 0,
    val lowBatteryCount: Int = 0,
    val lowBatteryMacs: Set<String> = emptySet(),
    val latestSyncAt: Long? = null,
    val latestAlert: RuleEvent? = null,
    val latestSceneRun: SceneRun? = null,
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
    /** 今日概览 / 设备状态 / 最近活动；没有数据时保持真实的 0 与空列表。 */
    val overview: HomeOverview = HomeOverview(),
    /** 在线阈值（最近 N 毫秒内出现）；与 DAO 计数共用同一口径。 */
    val onlineSince: Long = 0L,
    /** 设备状态下钻筛选；null 表示不筛选。 */
    val statusFilter: HomeStatusFilter? = null,
    /** 下拉刷新进行中。 */
    val refreshing: Boolean = false,
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
    private val sceneRunRepository: SceneRunRepository,
    private val roomRepository: RoomRepository,
    private val sceneTriggerRunner: SceneTriggerRunner,
    private val scanLogRepository: ScanLogRepository,
    private val bleScanner: BleScanner,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val syncManager: SyncManager,
    private val settings: AppSettings,
    private val householdRepository: HouseholdRepository,
    private val context: Application,
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

    private val _statusFilter = MutableStateFlow<HomeStatusFilter?>(null)
    private val _refreshing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 今日窗口按本地 0 点切分；跨零点下拉刷新时会重新计算。 */
    private val _dayWindow = MutableStateFlow(todayWindow())
    private val _onlineSince = MutableStateFlow(System.currentTimeMillis() - ONLINE_WINDOW_MS)

    private fun <T> dayFlow(block: suspend (Long, Long) -> Flow<T>): Flow<T> =
        _dayWindow.flatMapLatest { (from, to) -> block(from, to) }

    /** 今日概览：读数总量、按指标/按小时分布、越界次数与最近一条告警。 */
    private val todayAggregates = combine(
        dayFlow { from, to -> readingRepository.observeCountBetween(from, to) },
        dayFlow { from, to -> readingRepository.observeMetricCountsBetween(from, to) },
        dayFlow { from, to -> readingRepository.observeHourlyCountsBetween(from, to) },
        dayFlow { from, to -> ruleEventRepository.observeMetricViolationCountBetween(from, to) },
        combine(
            dayFlow { from, to -> ruleEventRepository.observeCountBetween(from, to) },
            dayFlow { from, to -> ruleEventRepository.observeLatestBetween(from, to) },
        ) { count, latest -> AlertSummary(count, latest) },
    ) { readings, metrics, hourly, violations, alerts ->
        TodayAggregate(readings, metrics, hourly, violations, alerts.count, alerts.latest)
    }

    /** 设备状态：在线/离线/未分配房间走 SQL 计数，低电量用每设备最新电量读数。 */
    private val deviceAggregates = combine(
        _onlineSince.flatMapLatest { deviceRepository.observeOnlineCount(it) },
        _onlineSince.flatMapLatest { deviceRepository.observeOfflineCount(it) },
        deviceRepository.observeUnassignedRoomCount(),
        readingRepository.observeLatestPerDeviceMetric(ReadingMetrics.BATTERY),
    ) { online, offline, unassigned, batteryReadings ->
        DeviceAggregate(online, offline, unassigned, batteryReadings)
    }

    /** 最近活动：最近同步时间与最近一次场景触发均来自真实记录。 */
    private val activityAggregates = combine(
        syncManager.status,
        sceneRunRepository.observeLatest(),
    ) { status, latestRun ->
        ActivityAggregate(status.lastSyncAt, latestRun)
    }

    private val homeContent = combine(
        deviceRepository.observeDevices().onEach { _loaded.value = true },
        readingRepository.observeLatestPerDevice(),
        roomRepository.observeRooms(),
    ) { devices, readings, rooms ->
        HomeContent(devices, readings, rooms)
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
        combine(todayAggregates, deviceAggregates, activityAggregates) { today, device, activity ->
            Dashboard(today, device, activity)
        },
        combine(sceneRepository.observeScenes(), _statusFilter, _refreshing) { scenes, filter, refreshing ->
            ScenePack(scenes, filter, refreshing)
        },
        combine(_query, _sortMode, _selectedGroup, _collapsedRooms) { query, sort, group, collapsed ->
            HomeFilters(query, sort, group, collapsed)
        },
        combine(bleConnectionManager.state, _loaded, syncManager.status) { connection, loaded, status ->
            ConnectionPack(connection, loaded, status.lastError)
        },
    ) { contentAndHousehold, dashboard, scenePack, filters, connectionPack ->
        val (content, household) = contentAndHousehold
        val (today, device, activity) = dashboard
        val (scenes, statusFilter, refreshing) = scenePack
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
        val visibleMacs = visibleDevices.mapTo(mutableSetOf()) { it.mac }
        val onlineSince = _onlineSince.value
        val lowBatteryMacs = device.batteryReadings
            .filter { it.value < LOW_BATTERY_THRESHOLD && it.deviceId in visibleMacs }
            .mapTo(mutableSetOf()) { it.deviceId }
        val statusDevices = when (statusFilter) {
            HomeStatusFilter.ONLINE -> visibleDevices.filter { it.lastSeenTimestamp >= onlineSince }
            HomeStatusFilter.OFFLINE -> visibleDevices.filter { it.lastSeenTimestamp < onlineSince }
            HomeStatusFilter.UNASSIGNED -> visibleDevices.filter { it.roomId == null }
            HomeStatusFilter.LOW_BATTERY -> visibleDevices.filter { it.mac in lowBatteryMacs }
            null -> visibleDevices
        }
        val sections = if (statusFilter == null) {
            sectionsOf(visibleDevices, visibleRooms, filters)
        } else {
            listOf(
                DeviceSection(
                    room = null,
                    devices = statusDevices.sortedWith(comparatorFor(filters.sort)),
                ),
            )
        }
        HomeUiState(
            devices = statusDevices,
            sections = sections,
            rooms = visibleRooms,
            collapsedRooms = filters.collapsed,
            searching = filters.query.isNotBlank(),
            latestReadings = newestPerDevice,
            connectedMac = (connectionPack.connection as? BleConnectionState.Connected)?.mac,
            alertCountToday = today.alertCount,
            scenes = scenes,
            groups = visibleDevices.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct().sorted(),
            selectedGroup = filters.group,
            query = filters.query,
            sortMode = filters.sort,
            loading = !connectionPack.loaded,
            syncError = connectionPack.syncError,
            households = household.households,
            currentHousehold = household.current,
            householdSynced = household.synced,
            overview = HomeOverview(
                todayReadings = today.readings,
                metricCounts = today.metricCounts,
                hourlyCounts = today.hourlyCounts,
                violationCount = today.violations,
                onlineCount = device.online,
                offlineCount = device.offline,
                unassignedCount = device.unassigned,
                lowBatteryCount = lowBatteryMacs.size,
                lowBatteryMacs = lowBatteryMacs,
                latestSyncAt = activity.lastSyncAt,
                latestAlert = today.latestAlert,
                latestSceneRun = activity.latestRun,
            ),
            onlineSince = onlineSince,
            statusFilter = statusFilter,
            refreshing = refreshing,
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

    /**
     * 整段重排房间：提交完整顺序给服务端；409 时仓库已重新拉取，这里只提示「已刷新」。
     * 离线时本地顺序保留，房间头照常显示「未同步」。
     */
    fun reorderRooms(orderedLocalIds: List<Long>) {
        viewModelScope.launch {
            runCatching { householdRepository.reorderRooms(orderedLocalIds) }
                .onFailure { error ->
                    if (error is RoomSetMismatchException) {
                        _householdMessages.tryEmit(context.getString(R.string.room_order_changed))
                    } else {
                        emitHouseholdError(error)
                    }
                }
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
                HouseholdErrorKind.CONFLICT -> context.getString(R.string.household_conflict)
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
            val result = sceneTriggerRunner.runManually(scene) { index, total, action ->
                _sceneProgress.value = SceneProgress(scene.id, scene.name, index, total, action)
            }
            _sceneProgress.value = null
            _sceneResults.tryEmit(result)
        }
    }

    /** 设备状态点击下钻；再次点击同一维度时取消筛选。 */
    fun toggleStatusFilter(filter: HomeStatusFilter) {
        _statusFilter.update { if (it == filter) null else filter }
    }

    fun clearStatusFilter() {
        _statusFilter.value = null
    }

    /** 下拉刷新：重算今日窗口与在线阈值，触发一次同步，结束后刷新聚合。 */
    fun refreshNow() {
        if (_refreshing.value) return
        _refreshing.value = true
        _dayWindow.value = todayWindow()
        _onlineSince.value = System.currentTimeMillis() - ONLINE_WINDOW_MS
        viewModelScope.launch {
            try {
                syncManager.manualSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(context.getString(R.string.home_refresh_failed))
            } finally {
                _refreshing.value = false
            }
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
        val rooms: List<Room>,
    )

    private data class HouseholdContent(
        val households: List<Household>,
        val current: Household?,
        val synced: Boolean,
    )

    private data class AlertSummary(
        val count: Int,
        val latest: RuleEvent?,
    )

    private data class TodayAggregate(
        val readings: Int,
        val metricCounts: List<MetricCount>,
        val hourlyCounts: List<HourCount>,
        val violations: Int,
        val alertCount: Int,
        val latestAlert: RuleEvent?,
    )

    private data class DeviceAggregate(
        val online: Int,
        val offline: Int,
        val unassigned: Int,
        val batteryReadings: List<Reading>,
    )

    private data class ActivityAggregate(
        val lastSyncAt: Long?,
        val latestRun: SceneRun?,
    )

    private data class Dashboard(
        val today: TodayAggregate,
        val device: DeviceAggregate,
        val activity: ActivityAggregate,
    )

    private data class ScenePack(
        val scenes: List<Scene>,
        val statusFilter: HomeStatusFilter?,
        val refreshing: Boolean,
    )

    private data class ConnectionPack(
        val connection: BleConnectionState,
        val loaded: Boolean,
        val syncError: String?,
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

    /**
     * 房间详情批量断开：BLE 层同一时刻只维护一条连接，
     * 仅当当前连接/连接中的设备在所选集合内时才真正断开，避免报出并不存在的成功数。
     */
    fun disconnectDevices(macs: List<String>) {
        val current = when (val state = bleConnectionManager.state.value) {
            is BleConnectionState.Connected -> state.mac
            is BleConnectionState.Connecting -> state.mac
            else -> null
        }
        if (current != null && current in macs) {
            bleConnectionManager.disconnect(current)
            _messages.tryEmit(context.getString(R.string.room_batch_disconnected))
        } else {
            _messages.tryEmit(context.getString(R.string.room_batch_disconnect_none))
        }
    }

    /** 房间详情批量标记：与场景「标记设备」动作使用同一份标记时间，供场景选择器识别。 */
    fun markDevices(macs: List<String>) {
        if (macs.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                macs.forEach { deviceRepository.markDevice(it, now) }
            }.onSuccess {
                // 标记走 /sync/push 字段级 LWW：先落本机 pending，再尽快触发上传；
                // 离线/未登录时保持 pending，由界面提示「等待上传」。
                syncScheduler.scheduleNow()
                _messages.tryEmit(context.getString(R.string.room_batch_marked, macs.size))
            }.onFailure {
                _messages.tryEmit(context.getString(R.string.room_batch_mark_failed))
            }
        }
    }

    /** 房间详情批量取消标记：本机先写 null + 脏位 + pending，随后随 /sync/push 上报。 */
    fun unmarkDevices(macs: List<String>) {
        if (macs.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                macs.forEach { deviceRepository.clearDeviceMark(it, now) }
            }.onSuccess {
                syncScheduler.scheduleNow()
                _messages.tryEmit(context.getString(R.string.room_batch_unmarked, macs.size))
            }.onFailure {
                _messages.tryEmit(context.getString(R.string.room_batch_unmark_failed))
            }
        }
    }

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

        /** 最近 15 分钟内出现视为在线；与 DAO 的 lastSeenTimestamp 计数共用。 */
        const val ONLINE_WINDOW_MS = 15 * 60 * 1000L

        /** 最新电量低于该值计为低电量。 */
        const val LOW_BATTERY_THRESHOLD = 20.0

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
                    sceneRunRepository = container.sceneRunRepository,
                    roomRepository = container.roomRepository,
                    sceneTriggerRunner = container.sceneTriggerRunner,
                    scanLogRepository = container.scanLogRepository,
                    bleScanner = container.bleScanner,
                    bleConnectionManager = container.bleConnectionManager,
                    ruleEngine = container.ruleEngine,
                    syncScheduler = container.syncScheduler,
                    syncManager = container.syncManager,
                    settings = container.settings,
                    householdRepository = container.householdRepository,
                    context = app,
                    unknownDeviceName = app.getString(R.string.device_unknown),
                )
            }
        }
    }
}
