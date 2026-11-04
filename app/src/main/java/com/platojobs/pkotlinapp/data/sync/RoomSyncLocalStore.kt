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
        val localMac = dao.findByExternalId(normalized)?.mac ?: normalized
        dao.insertOrUpdate(record.toEntity(macOverride = localMac))
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
    )
}
