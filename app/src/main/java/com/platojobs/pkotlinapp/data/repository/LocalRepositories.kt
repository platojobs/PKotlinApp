package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.local.RuleDao
import com.platojobs.pkotlinapp.data.local.RuleEntity
import com.platojobs.pkotlinapp.data.local.RuleEventDao
import com.platojobs.pkotlinapp.data.local.RuleEventEntity
import com.platojobs.pkotlinapp.data.local.ScanLogDao
import com.platojobs.pkotlinapp.data.local.ScanLogEntity
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.model.ruleTypeOf
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
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
)

private fun RuleEntity.toDomain() = DeviceRule(
    id = id,
    type = ruleTypeOf(type),
    threshold = threshold,
    enabled = enabled,
    createdAt = createdAt,
    lastTriggeredTimestamp = lastTriggeredTimestamp,
)

private fun RuleEventEntity.toDomain() = RuleEvent(
    id = id,
    ruleType = ruleTypeOf(ruleType),
    mac = mac,
    message = message,
    timestamp = timestamp,
)

private fun RuleEvent.toEntity() = RuleEventEntity(
    ruleType = ruleType.name,
    mac = mac,
    message = message,
    timestamp = timestamp,
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
