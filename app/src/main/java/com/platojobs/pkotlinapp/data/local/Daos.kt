package com.platojobs.pkotlinapp.data.local

import androidx.room.Dao
import androidx.room.Insert
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

    /** 重新登录到新匿名账号后，把本机全部记录重新排队，推送到新账号。 */
    @Query("UPDATE devices SET syncState = 'pending'")
    suspend fun markAllPending()

    @Query("DELETE FROM devices WHERE externalId = :externalId")
    suspend fun deleteByExternalId(externalId: String)
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
