package com.platojobs.pkotlinapp.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
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

    /** 本地房间归属：null 表示移出房间回到「未分配」。 */
    @Query("UPDATE devices SET roomId = :roomId WHERE mac = :mac")
    suspend fun assignRoom(mac: String, roomId: Long?)

    /** 删除房间前把房间内设备回到未分配，避免悬空 roomId。 */
    @Query("UPDATE devices SET roomId = NULL WHERE roomId = :roomId")
    suspend fun clearRoomAssignments(roomId: Long)

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

/** 本地房间 DAO：房间与设备归属仅存本机。 */
@Dao
interface RoomDao {

    @Query("SELECT * FROM rooms ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeRooms(): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun getRooms(): List<RoomEntity>

    @Insert
    suspend fun insert(room: RoomEntity): Long

    @Query("UPDATE rooms SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun delete(id: Long)
}

/** 指标聚合行：离线时替代服务端 `/readings/metrics` 的本地兜底。 */
data class ReadingMetricRow(
    val metric: String,
    val unit: String,
    val count: Long,
    val firstAt: Long,
    val lastAt: Long,
)

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

    /**
     * 每台设备最近一条读数（首页卡片展示；同一时刻多条时由调用方取第一条）。
     * 只用真实落库数据，不造假。
     */
    @Query(
        """
        SELECT r.* FROM readings r
        INNER JOIN (
            SELECT deviceId, MAX(recordedAt) AS maxAt FROM readings GROUP BY deviceId
        ) m ON r.deviceId = m.deviceId AND r.recordedAt = m.maxAt
        ORDER BY r.recordedAt DESC
        """,
    )
    fun observeLatestPerDevice(): Flow<List<ReadingEntity>>

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

    /** 导出用：闭区间内的本地读数，按时间正序。 */
    @Query(
        "SELECT * FROM readings WHERE deviceId = :deviceId AND metric = :metric " +
            "AND recordedAt >= :from AND recordedAt <= :to ORDER BY recordedAt ASC, id ASC",
    )
    suspend fun getBetween(
        deviceId: String,
        metric: String,
        from: Long,
        to: Long,
    ): List<ReadingEntity>

    /**
     * 指标清单的本地兜底：按 lastAt 倒序、同一时刻按 metric 升序，与服务端口径一致；
     * unit 取该指标 recordedAt 最大的一条（历史读数可能换过单位）。
     */
    @Query(
        """
        SELECT metric,
               (SELECT r2.unit FROM readings r2
                 WHERE r2.deviceId = readings.deviceId AND r2.metric = readings.metric
                 ORDER BY r2.recordedAt DESC, r2.id DESC LIMIT 1) AS unit,
               COUNT(*) AS count,
               MIN(recordedAt) AS firstAt,
               MAX(recordedAt) AS lastAt
        FROM readings
        WHERE deviceId = :deviceId
        GROUP BY metric
        ORDER BY lastAt DESC, metric ASC
        """,
    )
    suspend fun metricAggregates(deviceId: String): List<ReadingMetricRow>

    @Query(
        "SELECT * FROM readings WHERE syncState = 'pending' " +
            "ORDER BY recordedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings WHERE syncState = 'pending'")
    suspend fun pendingCount(): Int

    @Query("UPDATE readings SET syncState = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** 接管/迁移到新账号后，把本机读数全部重新排队上传。 */
    @Query("UPDATE readings SET syncState = 'pending'")
    suspend fun markAllPending()

    /** 本地只保留 7 天；pending 记录无论多旧都必须保留，避免离线数据被清掉。 */
    @Query("DELETE FROM readings WHERE recordedAt < :cutoff AND syncState = 'synced'")
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

    /** 今日真实触发条数（首页告警摘要）。 */
    @Query("SELECT COUNT(*) FROM rule_events WHERE timestamp >= :from AND timestamp < :to")
    fun observeCountBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT * FROM rule_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RuleEventEntity>

    @Query("DELETE FROM rule_events")
    suspend fun clear()
}

/** 场景及其有序动作；@Relation 由 Room 在事务内一次性取回动作列表。 */
data class SceneWithActions(
    @Embedded val scene: SceneEntity,
    @Relation(parentColumn = "id", entityColumn = "sceneId")
    val actions: List<SceneActionEntity>,
)

@Dao
interface SceneDao {

    @Transaction
    @Query("SELECT * FROM scenes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SceneWithActions>>

    @Transaction
    @Query("SELECT * FROM scenes ORDER BY createdAt ASC")
    suspend fun getAll(): List<SceneWithActions>

    @Insert
    suspend fun insertScene(scene: SceneEntity): Long

    @Insert
    suspend fun insertActions(actions: List<SceneActionEntity>)

    @Query("UPDATE scenes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM scene_actions WHERE sceneId = :sceneId")
    suspend fun deleteActions(sceneId: Long)

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)
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
