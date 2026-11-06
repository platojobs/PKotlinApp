package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.local.RoomDao
import com.platojobs.pkotlinapp.data.local.RoomEntity
import com.platojobs.pkotlinapp.data.local.RuleDao
import com.platojobs.pkotlinapp.data.local.RuleEntity
import com.platojobs.pkotlinapp.data.local.RuleEventDao
import com.platojobs.pkotlinapp.data.local.RuleEventEntity
import com.platojobs.pkotlinapp.data.local.ScanLogDao
import com.platojobs.pkotlinapp.data.local.ScanLogEntity
import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.local.SceneDao
import com.platojobs.pkotlinapp.data.local.SceneEntity
import com.platojobs.pkotlinapp.data.local.SceneWithActions
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.model.ruleTypeOf
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.repository.RoomRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.data.local.SceneRunDao
import com.platojobs.pkotlinapp.data.local.SceneRunEntity
import com.platojobs.pkotlinapp.domain.repository.SceneRunRepository
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import com.platojobs.pkotlinapp.domain.scene.latestRunByScene
import com.platojobs.pkotlinapp.data.scene.SceneCanonical
import com.platojobs.pkotlinapp.domain.scene.SceneSyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID

class DeviceRepositoryImpl(
    private val dao: DeviceDao,
) : DeviceRepository {

    override suspend fun saveDevice(device: Device) {
        val normalizedExternalId = normalizeExternalId(
            device.externalId.ifBlank { device.mac },
        )
        val existing = dao.findAny(device.mac) ?: dao.findByExternalId(normalizedExternalId)
        val clientId = device.id.ifBlank {
            existing?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        }
        dao.insertOrUpdate(
            device.copy(
                mac = existing?.mac ?: device.mac,
                // 扫描只写 advertisedName；用户别名只由详情页编辑写入，两者互不覆盖。
                // aliasDirty 置位后允许写入空串（显式清空别名）。
                name = if (device.aliasDirty) {
                    device.name
                } else {
                    device.name.takeIf { it.isNotBlank() } ?: existing?.name.orEmpty()
                },
                aliasDirty = device.aliasDirty || existing?.aliasDirty == true,
                createdAt = existing?.createdAt?.takeIf { it > 0 }
                    ?: device.createdAt.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                advertisedName = device.advertisedName ?: existing?.advertisedName,
                group = device.group ?: existing?.group,
                // 房间归属只由 assignRoom 修改；扫描/编辑设备时保留原值。
                roomId = existing?.roomId ?: device.roomId,
                householdId = device.householdId ?: existing?.householdId,
                remoteRoomId = device.remoteRoomId ?: existing?.remoteRoomId,
                pendingRoomPatch = device.pendingRoomPatch || existing?.pendingRoomPatch == true,
                pendingRoomLocalId = existing?.pendingRoomLocalId ?: device.pendingRoomLocalId,
                pendingHouseholdRemoteId =
                    existing?.pendingHouseholdRemoteId ?: device.pendingHouseholdRemoteId,
                shared = existing?.shared ?: device.shared,
                deviceType = device.deviceType ?: existing?.deviceType,
                deviceTypeName = device.deviceTypeName ?: existing?.deviceTypeName,
                vendor = device.vendor ?: existing?.vendor,
                firmwareVersion = device.firmwareVersion ?: existing?.firmwareVersion,
                compatibility = device.compatibility ?: existing?.compatibility,
                id = clientId,
                platform = PLATFORM_ANDROID,
                externalId = normalizedExternalId,
                updatedAt = maxOf(device.updatedAt, device.lastSeenTimestamp),
                deletedAt = null,
                syncState = SyncState.PENDING,
            ).toEntity(
                // 扫描/编辑不会改标记；保留已有 markedAt 与待上报位。
                markedAt = existing?.markedAt ?: device.markedAt,
                markedAtDirty = existing?.markedAtDirty == true,
            ),
        )
    }

    override suspend fun getDevices(): List<Device> = dao.getAllDevices().map { it.toDomain() }

    override fun observeDevices(): Flow<List<Device>> =
        dao.observeDevices().map { list -> list.map { it.toDomain() } }

    override suspend fun getDevice(mac: String): Device? = dao.find(mac)?.toDomain()

    override fun observeDevice(mac: String): Flow<Device?> =
        dao.observe(mac).map { it?.toDomain() }

    override suspend fun deleteDevice(mac: String) {
        dao.tombstone(mac, System.currentTimeMillis())
    }

    override suspend fun assignRoom(mac: String, roomId: Long?) {
        dao.assignRoom(mac, roomId)
    }

    override suspend fun markDevice(mac: String, timestamp: Long) {
        dao.markDevice(mac, timestamp)
    }

    override suspend fun clearDeviceMark(mac: String, timestamp: Long) {
        dao.clearDeviceMark(mac, timestamp)
    }

    override fun observeOnlineCount(since: Long): Flow<Int> = dao.observeOnlineCount(since)

    override fun observeOfflineCount(since: Long): Flow<Int> = dao.observeOfflineCount(since)

    override fun observeUnassignedRoomCount(): Flow<Int> = dao.observeUnassignedRoomCount()

    override fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    override fun observeDevicesByOnlineState(online: Boolean, since: Long): Flow<List<Device>> =
        dao.observeByOnlineState(online, since).map { list -> list.map { it.toDomain() } }

    override fun observeUnassignedRoomDevices(): Flow<List<Device>> =
        dao.observeUnassignedRoomDevices().map { list -> list.map { it.toDomain() } }

    override fun observeDevicesByMacs(macs: List<String>): Flow<List<Device>> {
        if (macs.isEmpty()) return flowOf(emptyList())
        return dao.observeByMacs(macs).map { list -> list.map { it.toDomain() } }
    }
}

class RuleRepositoryImpl(
    private val dao: RuleDao,
) : RuleRepository {

    override fun observeRules(): Flow<List<DeviceRule>> =
        dao.observeRules().map { list -> list.map { it.toDomain() } }

    override suspend fun getRules(): List<DeviceRule> = dao.getAllRules().map { it.toDomain() }

    override suspend fun addRule(type: RuleType, threshold: Int): Long = dao.insert(
        RuleEntity(
            type = type.name,
            threshold = threshold,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastTriggeredTimestamp = 0L,
        ),
    )

    override suspend fun addMetricRule(
        metric: String,
        comparator: RuleComparator,
        thresholdValue: Double,
        consecutive: Int,
    ): Long = dao.insert(
        RuleEntity(
            type = RuleType.METRIC_THRESHOLD.name,
            threshold = 0,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastTriggeredTimestamp = 0L,
            metric = metric,
            comparator = comparator.name,
            thresholdValue = thresholdValue,
            consecutive = MetricThreshold.sanitizeConsecutive(consecutive),
        ),
    )

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun deleteRule(id: Long) = dao.delete(id)

    override suspend fun markTriggered(id: Long, timestamp: Long) =
        dao.markTriggered(id, timestamp)
}

class RuleEventRepositoryImpl(
    private val dao: RuleEventDao,
) : RuleEventRepository {

    override fun observeRecent(limit: Int): Flow<List<RuleEvent>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeCountBetween(from: Long, to: Long): Flow<Int> =
        dao.observeCountBetween(from, to)

    override fun observeMetricViolationCountBetween(from: Long, to: Long): Flow<Int> =
        dao.observeMetricViolationCountBetween(from, to)

    override fun observeLatestBetween(from: Long, to: Long): Flow<RuleEvent?> =
        dao.observeLatestBetween(from, to).map { it?.toDomain() }

    override suspend fun getRecent(limit: Int): List<RuleEvent> =
        dao.getRecent(limit).map { it.toDomain() }

    override suspend fun add(event: RuleEvent): Long = dao.insert(event.toEntity())

    override suspend fun clear() = dao.clear()
}

class ScanLogRepositoryImpl(
    private val dao: ScanLogDao,
) : ScanLogRepository {
    override fun observeRecent(limit: Int): Flow<List<ScanLog>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun add(log: ScanLog) {
        dao.insert(log.toEntity())
    }

    override suspend fun clear() = dao.clear()
}

class SceneRepositoryImpl(
    private val dao: SceneDao,
    private val deviceLookup: SceneDeviceLookup = SceneDeviceLookup.Empty,
    private val now: () -> Long = System::currentTimeMillis,
) : SceneRepository {

    override fun observeScenes(): Flow<List<Scene>> =
        combine(dao.observeAll(), deviceLookup.observeKnown()) { rows, known ->
            rows.map { it.toDomain(known) }
        }

    override suspend fun getScenes(): List<Scene> =
        dao.getAll().map { it.toDomain(knownMacs()) }

    override suspend fun getScene(id: Long): Scene? =
        dao.getAll().firstOrNull { it.scene.id == id }?.toDomain(knownMacs())

    override suspend fun findSceneByRuleId(ruleId: Long): Scene? =
        dao.getAll().firstOrNull { row -> row.rules.any { it.id == ruleId } }
            ?.toDomain(knownMacs())

    override suspend fun saveScene(
        id: Long?,
        name: String,
        actions: List<SceneAction>,
        trigger: SceneTrigger?,
        enabled: Boolean,
    ): Long {
        val timestamp = now()
        val preparedTrigger = trigger?.let { resolveDeviceType(it) }
        val encoded = preparedTrigger?.takeIf { it != SceneTrigger.Manual }
            ?.let { SceneCanonical.encode(it, actions) }
        val rule = trigger?.takeIf { it != SceneTrigger.Manual }
            ?.let { SceneCanonical.ruleEntity(it, enabled) }
        val actionEntities = SceneCanonical.actionEntities(actions)
        if (id == null) {
            val syncState = if (encoded != null) SceneSyncState.PENDING else SceneSyncState.LOCAL_ONLY
            return dao.createSceneWithRule(
                scene = SceneEntity(
                    name = name,
                    createdAt = timestamp,
                    enabled = enabled,
                    syncState = syncState.storage,
                    syncError = unsupportedReason(trigger, actions, encoded != null),
                    nameUpdatedAt = timestamp,
                    enabledUpdatedAt = timestamp,
                    cooldownUpdatedAt = timestamp,
                    triggerUpdatedAt = timestamp,
                    actionsUpdatedAt = timestamp,
                    triggerJson = encoded?.triggerJson,
                    actionsJson = encoded?.actionsJson,
                ),
                rule = rule,
                actions = actionEntities,
            )
        }

        val existing = dao.find(id) ?: return id
        dao.updateSceneWithRule(
            sceneId = id,
            name = name,
            enabled = enabled,
            rule = rule,
            actions = actionEntities,
        )
        // 只更新真正变化的字段，保留服务端字段级 LWW 的粒度。
        if (existing.name != name) dao.updateNameForSync(id, name, timestamp)
        if (existing.enabled != enabled) dao.updateEnabledForSync(id, enabled, timestamp)
        val triggerChanged = existing.triggerJson != encoded?.triggerJson
        val actionsChanged = existing.actionsJson != encoded?.actionsJson
        if (encoded != null) {
            if (triggerChanged) dao.updateTriggerForSync(id, encoded.triggerJson, timestamp)
            if (actionsChanged) dao.updateActionsForSync(id, encoded.actionsJson, timestamp)
            if (existing.syncState != SceneSyncState.SYNCED.storage) {
                dao.markSyncState(id, SceneSyncState.PENDING.storage, null)
            }
        } else if (existing.remoteId == null) {
            dao.markSyncState(
                id,
                SceneSyncState.LOCAL_ONLY.storage,
                unsupportedReason(trigger, actions, false),
            )
        }
        return id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        dao.setRulesEnabled(id, enabled)
        dao.updateEnabledForSync(id, enabled, now())
    }

    /** 已上传场景走软删 tombstone，等同步层 DELETE 成功后再物理删除；仅本机场景直接删。 */
    override suspend fun deleteScene(id: Long) {
        val entity = dao.find(id)
        if (entity?.remoteId == null) {
            dao.deleteSceneWithRule(id)
        } else {
            dao.tombstone(id, now())
            dao.setRulesEnabled(id, false)
        }
    }

    private suspend fun knownMacs(): Set<String> = deviceLookup.known()

    private suspend fun deviceTypeOf(mac: String): String? = deviceLookup.deviceType(mac)

    /** metric_threshold 上传时必须带目录型号；本机旧场景通常没存，这里按设备补齐。 */
    private suspend fun resolveDeviceType(trigger: SceneTrigger): SceneTrigger =
        if (trigger is SceneTrigger.MetricThreshold && trigger.deviceType.isNullOrBlank()) {
            trigger.copy(deviceType = trigger.deviceMac?.let { deviceTypeOf(it) })
        } else {
            trigger
        }

    private fun unsupportedReason(
        trigger: SceneTrigger?,
        actions: List<SceneAction>,
        representable: Boolean,
    ): String? = when {
        representable -> null
        trigger == null || trigger == SceneTrigger.Manual ->
            "手动场景无需上传；若需跨设备使用请配置受支持的触发条件"
        actions.any { it.serverKind == null } ->
            "动作包含设备连接/采集等仅本机能力，服务端无法执行"
        trigger is SceneTrigger.Disconnected ->
            "全局断开触发未绑定设备，服务端无法表达"
        trigger is SceneTrigger.LowRssi && trigger.deviceMac.isNullOrBlank() ->
            "RSSI 触发未绑定设备"
        trigger is SceneTrigger.MetricThreshold && trigger.deviceMac.isNullOrBlank() ->
            "指标触发未绑定设备"
        trigger is SceneTrigger.MetricThreshold && trigger.deviceType == null ->
            "指标触发缺少设备型号（未在目录中注册）"
        trigger is SceneTrigger.Schedule && trigger.timeOfDay == null ->
            "本机间隔定时服务端不支持，需改为每天固定时刻"
        else -> "服务端模型不支持该场景"
    }
}

/** 场景执行记录仓库：只做真实落库与读取，不生成界面伪数据。 */
class SceneRunRepositoryImpl(
    private val dao: SceneRunDao,
) : SceneRunRepository {

    override fun observeRecent(limit: Int): Flow<List<SceneRun>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeLatest(): Flow<SceneRun?> =
        dao.observeLatest().map { it?.toDomain() }

    override fun observeLatestPerScene(): Flow<Map<Long, SceneRun>> =
        dao.observeLatestPerScene().map { rows ->
            latestRunByScene(rows.map { it.toDomain() })
        }

    override suspend fun getRecent(limit: Int): List<SceneRun> =
        dao.getRecent(limit).map { it.toDomain() }

    override suspend fun add(run: SceneRun): Long = dao.insert(run.toEntity())

    override suspend fun clear() = dao.clear()
}

/** 场景需要的设备信息：已知设备集合（判断 deviceMissing）与型号（上传 metric 条件）。 */
interface SceneDeviceLookup {
    fun observeKnown(): Flow<Set<String>>
    suspend fun known(): Set<String>
    suspend fun deviceType(mac: String): String?

    /** 测试/离线兜底：没有设备信息时不标 deviceMissing，也不补型号。 */
    object Empty : SceneDeviceLookup {
        override fun observeKnown(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun known(): Set<String> = emptySet()
        override suspend fun deviceType(mac: String): String? = null
    }
}

/** Room 实现：MAC 与 externalId 都视为设备身份，统一大写比较。 */
class RoomSceneDeviceLookup(private val deviceDao: DeviceDao) : SceneDeviceLookup {
    override fun observeKnown(): Flow<Set<String>> =
        deviceDao.observeDevices().map { devices -> devices.toKnownSet() }

    override suspend fun known(): Set<String> = deviceDao.getAllDevices().toKnownSet()

    override suspend fun deviceType(mac: String): String? = deviceDao.find(mac)?.deviceType

    private fun List<com.platojobs.pkotlinapp.data.local.DeviceEntity>.toKnownSet(): Set<String> =
        flatMap { listOf(it.mac, it.externalId) }
            .map { it.uppercase(Locale.US) }
            .toSet()
}

/**
 * 场景映射：有规范化 JSON 时以 JSON 为唯一真值（服务端场景），
 * 否则回落到 rules/scene_actions 投影（本机旧场景）。
 */
private fun SceneWithActions.toDomain(knownMacs: Set<String>): Scene {
    val canonicalTrigger = SceneCanonical.triggerFrom(SceneCanonical.triggerDto(scene.triggerJson))
    val trigger = canonicalTrigger ?: rules.firstOrNull()?.let { rule ->
        when (ruleTypeOf(rule.type)) {
            RuleType.METRIC_THRESHOLD -> SceneTrigger.MetricThreshold(
                metric = rule.metric.orEmpty(),
                comparator = RuleComparator.parse(rule.comparator),
                thresholdValue = rule.thresholdValue,
                consecutive = MetricThreshold.sanitizeConsecutive(rule.consecutive),
                deviceMac = rule.deviceMac,
            )

            RuleType.LOW_RSSI -> SceneTrigger.LowRssi(rule.threshold, rule.deviceMac)

            RuleType.DEVICE_OFFLINE -> rule.deviceMac?.let {
                SceneTrigger.DeviceOffline(it, rule.offlineSeconds)
            }

            RuleType.SCHEDULE -> SceneTrigger.Schedule(
                intervalMinutes = rule.intervalMinutes ?: 30,
                timeOfDay = rule.timeOfDay,
                timezone = rule.timezone ?: "UTC",
                daysOfWeek = rule.daysOfWeek.orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..7 }
                    .toSet(),
            )

            RuleType.DISCONNECTED, RuleType.BOND_LOST -> SceneTrigger.Disconnected
        }
    }
    val canonicalActions = SceneCanonical.actionsFrom(
        SceneCanonical.actionDtos(scene.actionsJson),
        trigger,
    ).takeIf { scene.actionsJson != null }
    val actions = canonicalActions ?: actions
        .sortedBy { it.position }
        .mapNotNull { entity ->
            val type = runCatching { SceneActionType.valueOf(entity.type) }.getOrNull()
                ?: return@mapNotNull null
            SceneAction(
                type = type,
                deviceMac = entity.deviceMac,
                muteMinutes = entity.muteMinutes,
                title = entity.title,
                body = entity.body,
                message = entity.message,
                level = entity.level,
                mark = entity.mark,
            )
        }
    val referencedMac = when (trigger) {
        is SceneTrigger.DeviceOffline -> trigger.deviceMac
        is SceneTrigger.LowRssi -> trigger.deviceMac
        is SceneTrigger.MetricThreshold -> trigger.deviceMac
        else -> null
    }?.uppercase(Locale.US)
    return Scene(
        id = scene.id,
        name = scene.name,
        createdAt = scene.createdAt,
        enabled = scene.enabled,
        trigger = trigger,
        actions = actions,
        remoteId = scene.remoteId,
        syncState = SceneSyncState.parse(scene.syncState),
        // 服务端列的 deviceMissing 是跨设备的真实值；本地已知设备再做一次兜底重算。
        deviceMissing = scene.deviceMissing || (referencedMac != null && referencedMac !in knownMacs),
        cooldownSeconds = scene.cooldownSeconds,
        syncError = scene.syncError,
    )
}

private fun SceneRunEntity.toDomain() = SceneRun(
    id = id,
    sceneId = sceneId,
    sceneName = sceneName,
    timestamp = timestamp,
    triggerType = triggerType,
    triggerValue = triggerValue,
    successCount = successCount,
    failureCount = failureCount,
    failureReason = failureReason,
    detail = detail,
)

private fun SceneRun.toEntity() = SceneRunEntity(
    sceneId = sceneId,
    sceneName = sceneName,
    timestamp = timestamp,
    triggerType = triggerType,
    triggerValue = triggerValue,
    successCount = successCount,
    failureCount = failureCount,
    failureReason = failureReason,
    detail = detail,
)

private fun DeviceEntity.toDomain() = Device(
    mac = mac,
    name = name,
    lastRssi = lastRssi,
    lastSeenTimestamp = lastSeenTimestamp,
    id = id,
    platform = platform,
    externalId = externalId,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncState = if (syncState == "synced") SyncState.SYNCED else SyncState.PENDING,
    deviceType = deviceType,
    vendor = vendor,
    firmwareVersion = firmwareVersion,
    compatibility = compatibility,
    deviceTypeName = deviceTypeName,
    group = group,
    advertisedName = advertisedName,
    aliasDirty = aliasDirty,
    createdAt = createdAt,
    roomId = roomId,
    householdId = householdId,
    remoteRoomId = remoteRoomId,
    shared = shared,
    pendingRoomPatch = pendingRoomPatch,
    markedAt = markedAt,
)

private fun Device.toEntity(
    markedAt: Long? = this.markedAt,
    markedAtDirty: Boolean = false,
) = DeviceEntity(
    mac = mac,
    name = name,
    lastRssi = lastRssi,
    lastSeenTimestamp = lastSeenTimestamp,
    id = id,
    platform = platform,
    externalId = externalId,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncState = syncState.name.lowercase(),
    deviceType = deviceType,
    vendor = vendor,
    firmwareVersion = firmwareVersion,
    compatibility = compatibility,
    deviceTypeName = deviceTypeName,
    group = group,
    advertisedName = advertisedName,
    aliasDirty = aliasDirty,
    createdAt = createdAt,
    roomId = roomId,
    householdId = householdId,
    remoteRoomId = remoteRoomId,
    pendingRoomPatch = pendingRoomPatch,
    pendingRoomLocalId = pendingRoomLocalId,
    pendingHouseholdRemoteId = pendingHouseholdRemoteId,
    shared = shared,
    markedAt = markedAt,
    markedAtDirty = markedAtDirty,
)

private fun RuleEntity.toDomain() = DeviceRule(
    id = id,
    type = ruleTypeOf(type),
    threshold = threshold,
    enabled = enabled,
    createdAt = createdAt,
    lastTriggeredTimestamp = lastTriggeredTimestamp,
    metric = metric,
    comparator = RuleComparator.parse(comparator),
    thresholdValue = thresholdValue,
    consecutive = MetricThreshold.sanitizeConsecutive(consecutive),
    sceneId = sceneId,
    intervalMinutes = intervalMinutes,
    deviceMac = deviceMac,
    offlineSeconds = offlineSeconds,
    timeOfDay = timeOfDay,
    timezone = timezone,
    daysOfWeek = daysOfWeek,
)

private fun RuleEventEntity.toDomain() = RuleEvent(
    id = id,
    ruleType = ruleTypeOf(ruleType),
    mac = mac,
    message = message,
    timestamp = timestamp,
    metric = metric,
    value = value,
    thresholdValue = thresholdValue,
    unit = unit,
)

private fun RuleEvent.toEntity() = RuleEventEntity(
    ruleType = ruleType.name,
    mac = mac,
    message = message,
    timestamp = timestamp,
    metric = metric,
    value = value,
    thresholdValue = thresholdValue,
    unit = unit,
)

private fun ScanLogEntity.toDomain() = ScanLog(
    id = id,
    mac = mac,
    name = name,
    rssi = rssi,
    timestamp = timestamp,
)

private fun ScanLog.toEntity() = ScanLogEntity(
    mac = mac,
    name = name,
    rssi = rssi,
    timestamp = timestamp,
)

class RoomRepositoryImpl(
    private val dao: RoomDao,
    private val deviceDao: DeviceDao,
) : RoomRepository {

    override fun observeRooms(): Flow<List<Room>> =
        dao.observeRooms().map { list -> list.map { it.toDomain() } }

    override suspend fun getRooms(): List<Room> = dao.getRooms().map { it.toDomain() }

    override suspend fun createRoom(name: String): Long =
        dao.insert(
            RoomEntity(
                name = name.trim(),
                sortOrder = dao.getRooms().size,
                createdAt = System.currentTimeMillis(),
            ),
        )

    override suspend fun renameRoom(id: Long, name: String) {
        dao.rename(id, name.trim())
    }

    /** 先清归属再删房间；即使中途失败留下悬空 roomId，展示层也按未分配处理。 */
    override suspend fun deleteRoom(id: Long) {
        deviceDao.clearRoomAssignments(id)
        dao.delete(id)
    }
}

private fun RoomEntity.toDomain() = Room(
    id = id,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    remoteId = remoteId,
    householdId = householdId,
)
