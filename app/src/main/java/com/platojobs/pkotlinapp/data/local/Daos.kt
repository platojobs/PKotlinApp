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

    @Query("SELECT * FROM devices ORDER BY lastSeenTimestamp DESC")
    suspend fun getAllDevices(): List<DeviceEntity>

    @Query("SELECT * FROM devices ORDER BY lastSeenTimestamp DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun find(mac: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    fun observe(mac: String): Flow<DeviceEntity?>

    @Query("DELETE FROM devices WHERE mac = :mac")
    suspend fun delete(mac: String)
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
