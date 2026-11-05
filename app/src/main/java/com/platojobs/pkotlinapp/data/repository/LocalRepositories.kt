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
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
            ).toEntity(),
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
) : SceneRepository {

    override fun observeScenes(): Flow<List<Scene>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getScenes(): List<Scene> =
        dao.getAll().map { it.toDomain() }

    override suspend fun saveScene(
        id: Long?,
        name: String,
        actions: List<SceneAction>,
    ): Long {
        val sceneId = if (id == null) {
            dao.insertScene(
                SceneEntity(name = name, createdAt = System.currentTimeMillis()),
            )
        } else {
            dao.rename(id, name)
            dao.deleteActions(id)
            id
        }
        if (actions.isNotEmpty()) {
            dao.insertActions(
                actions.mapIndexed { index, action ->
                    SceneActionEntity(
                        sceneId = sceneId,
                        position = index,
                        type = action.type.name,
                        deviceMac = action.deviceMac,
                        muteMinutes = action.muteMinutes,
                    )
                },
            )
        }
        return sceneId
    }

    override suspend fun deleteScene(id: Long) {
        dao.delete(id)
    }
}

private fun SceneWithActions.toDomain(): Scene = Scene(
    id = scene.id,
    name = scene.name,
    createdAt = scene.createdAt,
    actions = actions
        .sortedBy { it.position }
        .mapNotNull { entity ->
            val type = runCatching { SceneActionType.valueOf(entity.type) }.getOrNull()
                ?: return@mapNotNull null
            SceneAction(
                type = type,
                deviceMac = entity.deviceMac,
                muteMinutes = entity.muteMinutes,
            )
        },
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
)

private fun Device.toEntity() = DeviceEntity(
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
)
