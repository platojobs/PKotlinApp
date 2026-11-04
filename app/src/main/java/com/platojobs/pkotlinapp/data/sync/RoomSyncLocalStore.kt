package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.sync.SyncLocalStore
import com.platojobs.pkotlinapp.domain.sync.SyncRecord

/** Room 适配：mac 作为本地主键，externalId 作为跨端唯一键。 */
class RoomSyncLocalStore(
    private val dao: DeviceDao,
) : SyncLocalStore {

    override suspend fun pending(limit: Int): List<SyncRecord> =
        dao.pendingDevices(limit).map { it.toSyncRecord() }

    override suspend fun findActive(externalId: String): SyncRecord? =
        dao.findByExternalId(externalId)?.takeIf { it.deletedAt == null }?.toSyncRecord()

    override suspend fun markSynced(externalIds: List<String>) {
        if (externalIds.isNotEmpty()) {
            dao.markSyncedByExternalIds(externalIds)
        }
    }

    override suspend fun replaceWithServer(record: SyncRecord) {
        val normalized = if (record.platform == PLATFORM_ANDROID) {
            normalizeExternalId(record.externalId)
        } else {
            record.externalId
        }
        // 复用本地已有主键，避免 externalId 相同但 mac 大小写不同导致重复行。
        val existing = dao.findByExternalId(normalized)
        val localMac = existing?.mac ?: normalized
        dao.insertOrUpdate(
            // 服务端不返回 catalog 展示名；用本地缓存补齐，避免断网降级丢失。
            // 可缺省字段（absent）解析为 null 时保留本地值；显式空串（如 group=""）会覆盖。
            record.copy(
                deviceTypeName = record.deviceTypeName ?: existing?.deviceTypeName,
                name = record.name.takeIf { it.isNotBlank() } ?: existing?.name.orEmpty(),
                advertisedName = record.advertisedName ?: existing?.advertisedName,
                group = record.group ?: existing?.group,
            ).toEntity(macOverride = localMac),
        )
    }

    override suspend fun deleteByExternalId(externalId: String) {
        dao.deleteByExternalId(externalId)
    }
}

private fun DeviceEntity.toSyncRecord() = SyncRecord(
    id = id,
    platform = platform,
    externalId = externalId.ifBlank { normalizeExternalId(mac) },
    name = name,
    lastRssi = lastRssi,
    lastSeenTimestamp = lastSeenTimestamp,
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
)

private fun SyncRecord.toEntity(macOverride: String? = null): DeviceEntity {
    val normalized = if (platform == PLATFORM_ANDROID) {
        normalizeExternalId(externalId)
    } else {
        externalId
    }
    return DeviceEntity(
        mac = macOverride ?: normalized,
        name = name,
        lastRssi = lastRssi,
        lastSeenTimestamp = lastSeenTimestamp,
        id = id,
        platform = platform,
        externalId = normalized,
        updatedAt = updatedAt,
        deletedAt = null,
        syncState = "synced",
        deviceType = deviceType,
        vendor = vendor,
        firmwareVersion = firmwareVersion,
        compatibility = compatibility,
        deviceTypeName = deviceTypeName,
        group = group,
        advertisedName = advertisedName,
    )
}
