package com.platojobs.pkotlinapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Upsert
    suspend fun insertOrUpdate(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE deletedAt IS NULL ORDER BY lastSeenTimestamp DESC")
    suspend fun getAllDevices(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE deletedAt IS NULL ORDER BY lastSeenTimestamp DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE mac = :mac AND deletedAt IS NULL LIMIT 1")
    suspend fun find(mac: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE mac = :mac AND deletedAt IS NULL LIMIT 1")
    fun observe(mac: String): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun findAny(mac: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE externalId = :externalId LIMIT 1")
    suspend fun findByExternalId(externalId: String): DeviceEntity?

    @Query("DELETE FROM devices WHERE mac = :mac")
    suspend fun delete(mac: String)

    /** 软删除：保留 tombstone 等待推送，UI 通过 deletedAt IS NULL 过滤。 */
    @Query(
        "UPDATE devices SET deletedAt = :timestamp, updatedAt = :timestamp, " +
            "syncState = 'pending' WHERE mac = :mac",
    )
    suspend fun tombstone(mac: String, timestamp: Long)

    @Query("SELECT * FROM devices WHERE syncState = 'pending' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun pendingDevices(limit: Int): List<DeviceEntity>

    @Query("SELECT COUNT(*) FROM devices WHERE syncState = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM devices WHERE syncState = 'pending'")
    suspend fun pendingCount(): Int

    @Query("UPDATE devices SET syncState = 'synced' WHERE externalId IN (:externalIds)")
    suspend fun markSyncedByExternalIds(externalIds: List<String>)

    /**
     * 只更新 enrich 缓存，不改变同步状态与本地修改时间。
     * 展示名使用 COALESCE：catalog 本次失败（null）时保留上一次缓存，避免离线降级丢失。
     */
    @Query(
        "UPDATE devices SET deviceType = :deviceType, vendor = :vendor, " +
            "firmwareVersion = :firmwareVersion, compatibility = :compatibility, " +
            "deviceTypeName = COALESCE(:deviceTypeName, deviceTypeName), " +
            "advertisedName = COALESCE(:advertisedName, advertisedName) " +
            "WHERE externalId = :externalId",
    )
    suspend fun updateEnrich(
        externalId: String,
        deviceType: String?,
        vendor: String?,
        firmwareVersion: String?,
        compatibility: String?,
        deviceTypeName: String?,
        advertisedName: String?,
    )

    /** 云端分组只在成功拿到 UserDevice 时更新，避免未同步设备把本地分组清空。 */
    @Query("UPDATE devices SET deviceGroup = :group WHERE externalId = :externalId")
    suspend fun updateGroup(externalId: String, group: String?)

    /** 重新登录到新匿名账号后，把本机全部记录重新排队，推送到新账号。 */
    @Query("UPDATE devices SET syncState = 'pending'")
    suspend fun markAllPending()

    @Query("DELETE FROM devices WHERE externalId = :externalId")
    suspend fun deleteByExternalId(externalId: String)
}

@Dao
interface ReadingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reading: ReadingEntity): Long

    /** 该设备真实出现过的指标（用于动态 Chip，不造假分类）。 */
    @Query(
        "SELECT DISTINCT metric FROM readings WHERE deviceId = :deviceId ORDER BY metric ASC",
    )
    fun observeMetrics(deviceId: String): Flow<List<String>>

    @Query(
        "SELECT * FROM readings WHERE deviceId = :deviceId AND metric = :metric " +
            "ORDER BY recordedAt DESC LIMIT :limit",
    )
    fun observeRecent(deviceId: String, metric: String, limit: Int): Flow<List<ReadingEntity>>

    @Query(
        "SELECT * FROM readings WHERE deviceId = :deviceId AND metric = :metric " +
            "ORDER BY recordedAt DESC LIMIT :limit",
    )
    suspend fun getRecent(deviceId: String, metric: String, limit: Int): List<ReadingEntity>

    @Query(
        "SELECT * FROM readings WHERE deviceId = :deviceId AND metric = :metric " +
            "AND recordedAt >= :from ORDER BY recordedAt ASC",
    )
    suspend fun getSince(deviceId: String, metric: String, from: Long): List<ReadingEntity>

    @Query(
        "SELECT * FROM readings WHERE syncState = 'pending' " +
            "ORDER BY recordedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings WHERE syncState = 'pending'")
    suspend fun pendingCount(): Int

    @Query("UPDATE readings SET syncState = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** 本地只保留 7 天；服务端保留 90 天。 */
    @Query("DELETE FROM readings WHERE recordedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface RuleDao {

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules ORDER BY createdAt ASC")
    fun observeRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY createdAt ASC")
    suspend fun getAllRules(): List<RuleEntity>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE rules SET lastTriggeredTimestamp = :timestamp WHERE id = :id")
    suspend fun markTriggered(id: Long, timestamp: Long)
}

@Dao
interface RuleEventDao {

    @Insert
    suspend fun insert(event: RuleEventEntity): Long

    @Query("SELECT * FROM rule_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RuleEventEntity>>

    @Query("SELECT * FROM rule_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RuleEventEntity>

    @Query("DELETE FROM rule_events")
    suspend fun clear()
}

@Dao
interface ScanLogDao {

    @Insert
    suspend fun insert(log: ScanLogEntity): Long

    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanLogEntity>>

    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ScanLogEntity>

    @Query("DELETE FROM scan_logs")
    suspend fun clear()
}
