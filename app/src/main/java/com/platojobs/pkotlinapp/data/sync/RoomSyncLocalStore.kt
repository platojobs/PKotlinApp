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
            // alias 是 UserDevice 的必返字段：空串表示已显式清空，必须覆盖本地旧值，
            // 否则「清空 → 同步 → 拉回」会复活旧别名。advertisedName/group 的 absent
            // 仍保留本地值（group 空串则同样按显式清空覆盖）。
            record.copy(
                deviceTypeName = record.deviceTypeName ?: existing?.deviceTypeName,
                name = record.name,
                aliasDirty = existing?.aliasDirty ?: record.aliasDirty,
                advertisedName = record.advertisedName ?: existing?.advertisedName,
                group = record.group ?: existing?.group,
                // 字段缺失=保持本地标记；显式 null/空串=取消；RFC3339=覆盖。
                markedAt = if (record.markedAtDirty) record.markedAt else existing?.markedAt,
                // lastSeenAt 只会前进；老后端不返回该字段时保留本地值，避免降级丢心跳。
                lastSeenAt = record.lastSeenAt ?: existing?.lastSeenAt,
            ).toEntity(
                macOverride = localMac,
                createdAt = existing?.createdAt?.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                roomId = existing?.roomId,
            ),
        )
    }

    override suspend fun applyServerPresence(externalId: String, lastSeenAt: Long?) {
        if (lastSeenAt == null) return
        val entity = dao.findByExternalId(externalId) ?: return
        dao.applyLastSeenAt(entity.mac, lastSeenAt)
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
    aliasDirty = aliasDirty,
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
    markedAt = markedAt,
    markedAtDirty = markedAtDirty,
    lastSeenAt = lastSeenAt,
)

private fun SyncRecord.toEntity(macOverride: String? = null, createdAt: Long = 0L, roomId: Long? = null): DeviceEntity {
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
        aliasDirty = aliasDirty,
        // 服务端落地后标记视为已确认，不再重复上报。
        markedAt = markedAt,
        markedAtDirty = false,
        // 服务端只读字段：随增量写入，不置脏、不推进 updatedAt（updatedAt 已用服务端值）。
        lastSeenAt = lastSeenAt,
        createdAt = createdAt,
        roomId = roomId,
    )
}
