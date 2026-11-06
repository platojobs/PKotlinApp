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

    /**
     * 房间归属变更：立即更新本地展示，并标记待上报。
     * [pendingLocalId] 为空表示清空房间（PATCH roomId=""）；[householdRemoteId] 随请求上报。
     */
    @Query(
        "UPDATE devices SET roomId = :roomId, remoteRoomId = :remoteRoomId, " +
            "pendingRoomPatch = 1, pendingRoomLocalId = :pendingLocalId, " +
            "pendingHouseholdRemoteId = :householdRemoteId WHERE mac = :mac",
    )
    suspend fun queueRoomAssignment(
        mac: String,
        roomId: Long?,
        remoteRoomId: String?,
        pendingLocalId: Long?,
        householdRemoteId: String?,
    )

    @Query(
        "SELECT * FROM devices WHERE pendingRoomPatch = 1 AND deletedAt IS NULL " +
            "ORDER BY lastSeenTimestamp ASC",
    )
    suspend fun pendingRoomPatches(): List<DeviceEntity>

    @Query(
        "UPDATE devices SET pendingRoomPatch = 0, pendingRoomLocalId = NULL, " +
            "pendingHouseholdRemoteId = NULL WHERE mac = :mac",
    )
    suspend fun finishRoomPatch(mac: String)

    /** 服务端设备快照：待上报的房间变更优先，不能被拉取覆盖；本机 pending 设备也不能被误标已同步。 */
    @Query(
        "UPDATE devices SET householdId = :householdId, remoteRoomId = :remoteRoomId, " +
            "roomId = CASE WHEN pendingRoomPatch = 1 THEN roomId ELSE :roomId END, " +
            "syncState = CASE WHEN syncState = 'pending' THEN syncState ELSE 'synced' END " +
            "WHERE mac = :mac",
    )
    suspend fun applyServerMeta(mac: String, householdId: Long?, remoteRoomId: String?, roomId: Long?)

    @Query("SELECT * FROM devices WHERE shared = 1 AND householdId = :householdId")
    suspend fun sharedDevicesForHousehold(householdId: Long): List<DeviceEntity>

    @Query("DELETE FROM devices WHERE mac IN (:macs)")
    suspend fun deleteByMacs(macs: List<String>)

    /** 删除房间前把房间内设备回到未分配，避免悬空 roomId / remoteRoomId。 */
    @Query("UPDATE devices SET roomId = NULL, remoteRoomId = NULL WHERE roomId = :roomId")
    suspend fun clearRoomAssignments(roomId: Long)

    @Query("DELETE FROM devices WHERE shared = 1")
    suspend fun deleteShared()

    /** 家庭被移除/退出后，清除该家庭下的设备归属，避免悬空 house/房间引用。 */
    @Query(
        "UPDATE devices SET householdId = NULL, roomId = NULL, remoteRoomId = NULL " +
            "WHERE householdId = :householdId",
    )
    suspend fun clearHouseholdAssignments(householdId: Long)

    /** 退出家庭后，该家庭的成员共享设备必须从本机移除。 */
    @Query("DELETE FROM devices WHERE shared = 1 AND householdId = :householdId")
    suspend fun deleteSharedForHousehold(householdId: Long)

    /** 重新登录 / 切换账号时清空家庭与远端房间缓存，本机房间保留并等待重新合并。 */
    @Query(
        "UPDATE devices SET householdId = NULL, remoteRoomId = NULL, pendingRoomPatch = 0, " +
            "pendingRoomLocalId = NULL, pendingHouseholdRemoteId = NULL",
    )
    suspend fun clearHouseholdLinks()

    @Query("SELECT * FROM devices WHERE syncState = 'pending' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun pendingDevices(limit: Int): List<DeviceEntity>

    @Query("SELECT COUNT(*) FROM devices WHERE syncState = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM devices WHERE syncState = 'pending'")
    suspend fun pendingCount(): Int

    /** 首页设备状态：在线设备数（最近出现时间在阈值内，且未删除）。 */
    @Query(
        "SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL AND lastSeenTimestamp >= :since",
    )
    fun observeOnlineCount(since: Long): Flow<Int>

    /** 首页设备状态：离线设备数（未删除且最近出现时间早于阈值）。 */
    @Query(
        "SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL AND lastSeenTimestamp < :since",
    )
    fun observeOfflineCount(since: Long): Flow<Int>

    /** 首页设备状态：未分配房间的设备数。 */
    @Query("SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL AND roomId IS NULL")
    fun observeUnassignedRoomCount(): Flow<Int>

    /** 首页下钻：按在线/离线筛选设备。 */
    @Query(
        "SELECT * FROM devices WHERE deletedAt IS NULL AND " +
            "(:online = 1 AND lastSeenTimestamp >= :since OR :online = 0 AND lastSeenTimestamp < :since) " +
            "ORDER BY lastSeenTimestamp DESC",
    )
    fun observeByOnlineState(online: Boolean, since: Long): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deletedAt IS NULL AND roomId IS NULL " +
        "ORDER BY lastSeenTimestamp DESC")
    fun observeUnassignedRoomDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deletedAt IS NULL AND mac IN (:macs) " +
        "ORDER BY lastSeenTimestamp DESC")
    fun observeByMacs(macs: List<String>): Flow<List<DeviceEntity>>

    @Query("SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL")
    fun observeActiveCount(): Flow<Int>

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

    /** 场景「标记设备」动作：真实写入标记时间，设备卡片据此展示标记。 */
    @Query("UPDATE devices SET markedAt = :timestamp WHERE mac = :mac")
    suspend fun markDevice(mac: String, timestamp: Long)

    /** 重新登录到新匿名账号后，把本机全部记录重新排队，推送到新账号。 */
    @Query("UPDATE devices SET syncState = 'pending'")
    suspend fun markAllPending()

    @Query("DELETE FROM devices WHERE externalId = :externalId")
    suspend fun deleteByExternalId(externalId: String)
}

/** 本地房间 DAO：remoteId 为空表示仅存本机、尚未同步。 */
@Dao
interface RoomDao {

    @Query("SELECT * FROM rooms ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeRooms(): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun getRooms(): List<RoomEntity>

    @Query("SELECT * FROM rooms WHERE householdId = :householdId OR householdId IS NULL " +
        "ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun getRoomsForHousehold(householdId: Long): List<RoomEntity>

    @Query("SELECT * FROM rooms WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): RoomEntity?

    @Query("SELECT * FROM rooms WHERE remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): RoomEntity?

    @Insert
    suspend fun insert(room: RoomEntity): Long

    @Query("UPDATE rooms SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        "UPDATE rooms SET remoteId = :remoteId, householdId = :householdId, " +
            "sortOrder = :sortOrder WHERE id = :id",
    )
    suspend fun linkRemote(id: Long, remoteId: String, householdId: Long, sortOrder: Int)

    /** 服务端仍是权威名称与顺序时更新本地缓存，但保留本地 id 以维持设备归属。 */
    @Query("UPDATE rooms SET name = :name, sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateFromServer(id: Long, name: String, sortOrder: Int)

    /** 整段重排的本地乐观顺序；服务端确认后由 [updateFromServer] 覆盖。 */
    @Query("UPDATE rooms SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** 本地改名/重排尚未上传：后续结构变更不得覆盖本地值。 */
    @Query("UPDATE rooms SET pendingEdit = 1 WHERE id = :id")
    suspend fun markPendingEdit(id: Long)

    /** 本地 pending 已上传成功：清除标记，并应用服务端确认后的名称/顺序。 */
    @Query(
        "UPDATE rooms SET pendingEdit = 0, serverName = NULL, serverSortOrder = NULL, " +
            "name = :name, sortOrder = :sortOrder WHERE id = :id",
    )
    suspend fun clearPendingEditWithServer(id: Long, name: String, sortOrder: Int)

    /** pending 期间收到的服务端值先暂存，等下次合并再生效。 */
    @Query("UPDATE rooms SET serverName = :name, serverSortOrder = :sortOrder WHERE id = :id")
    suspend fun stageServerValue(id: Long, name: String, sortOrder: Int)

    /** 放弃本地 pending（例如 403），后续合并重新以服务端值为准。 */
    @Query("UPDATE rooms SET pendingEdit = 0, serverName = NULL, serverSortOrder = NULL WHERE id = :id")
    suspend fun clearPendingEdit(id: Long)

    /** v12 迁移后的旧房间还没有家庭归属，首次同步归入当前家庭。 */
    @Query("UPDATE rooms SET householdId = :householdId WHERE householdId IS NULL")
    suspend fun adoptOrphanRooms(householdId: Long)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rooms WHERE householdId = :householdId")
    suspend fun deleteForHousehold(householdId: Long)

    /** 重新登录后清空远端映射，本机房间保留并等待重新上传。 */
    @Query("UPDATE rooms SET remoteId = NULL, householdId = NULL")
    suspend fun clearRemoteLinks()
}

/** 本地家庭 DAO。 */
@Dao
interface HouseholdDao {

    @Query("SELECT * FROM households ORDER BY isPersonal DESC, sortOrder ASC, id ASC")
    fun observeHouseholds(): Flow<List<HouseholdEntity>>

    @Query("SELECT * FROM households ORDER BY isPersonal DESC, sortOrder ASC, id ASC")
    suspend fun getAll(): List<HouseholdEntity>

    @Query("SELECT * FROM households WHERE remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): HouseholdEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(household: HouseholdEntity): Long

    @Query(
        "UPDATE households SET name = :name, isPersonal = :isPersonal, role = :role, " +
            "memberCount = :memberCount, deviceCount = :deviceCount, roomCount = :roomCount, " +
            "sortOrder = :sortOrder, createdAt = :createdAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        isPersonal: Boolean,
        role: String,
        memberCount: Int,
        deviceCount: Int,
        roomCount: Int,
        sortOrder: Int,
        createdAt: Long,
    )

    /** change_log 结构同步：只更新接收者可感知的字段，不动个人家庭标记/创世时间。 */
    @Query(
        "UPDATE households SET name = :name, role = :role, memberCount = :memberCount " +
            "WHERE id = :id",
    )
    suspend fun updateStructural(id: Long, name: String, role: String, memberCount: Int)

    @Query("DELETE FROM households WHERE remoteId NOT IN (:remoteIds)")
    suspend fun deleteMissing(remoteIds: List<String>)

    @Query("DELETE FROM households WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM households")
    suspend fun clear()
}

/** 指标聚合行：离线时替代服务端 `/readings/metrics` 的本地兜底。 */
data class ReadingMetricRow(
    val metric: String,
    val unit: String,
    val count: Long,
    val firstAt: Long,
    val lastAt: Long,
)

/** 首页今日概览：按指标分组的真实读数条数。 */
data class MetricCountRow(
    val metric: String,
    val count: Int,
)

/** 首页今日概览：按小时分组的真实读数条数（0 起，相对本地当天 0 点）。 */
data class HourCountRow(
    val hourIndex: Int,
    val count: Int,
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

    /** 首页今日概览：本地当天窗口内的真实读数总量。 */
    @Query("SELECT COUNT(*) FROM readings WHERE recordedAt >= :from AND recordedAt < :to")
    fun observeCountBetween(from: Long, to: Long): Flow<Int>

    /** 首页今日概览：按指标 GROUP BY，避免把全表拉到内存里再统计。 */
    @Query(
        """
        SELECT metric AS metric, COUNT(*) AS count FROM readings
        WHERE recordedAt >= :from AND recordedAt < :to
        GROUP BY metric
        ORDER BY count DESC, metric ASC
        """,
    )
    fun observeMetricCountsBetween(from: Long, to: Long): Flow<List<MetricCountRow>>

    /** 首页今日概览：按小时 GROUP BY（小时序号相对本地 0 点）。 */
    @Query(
        """
        SELECT CAST((recordedAt - :from) / 3600000 AS INTEGER) AS hourIndex,
               COUNT(*) AS count
        FROM readings
        WHERE recordedAt >= :from AND recordedAt < :to
        GROUP BY hourIndex
        ORDER BY hourIndex ASC
        """,
    )
    fun observeHourlyCountsBetween(from: Long, to: Long): Flow<List<HourCountRow>>

    /** 每台设备某个指标的最新一条读数（首页低电量下钻/统计）。 */
    @Query(
        """
        SELECT r.* FROM readings r
        INNER JOIN (
            SELECT deviceId, MAX(recordedAt) AS maxAt FROM readings
            WHERE metric = :metric GROUP BY deviceId
        ) m ON r.deviceId = m.deviceId AND r.recordedAt = m.maxAt
        WHERE r.metric = :metric
        """,
    )
    fun observeLatestPerDeviceMetric(metric: String): Flow<List<ReadingEntity>>

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

    /** 自动化规则列表：场景触发规则由场景编辑器管理，不在此重复展示。 */
    @Query("SELECT * FROM rules WHERE sceneId IS NULL ORDER BY createdAt ASC")
    fun observeRules(): Flow<List<RuleEntity>>

    /** 引擎判定需要全部规则（含场景触发规则）。 */
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

    /** 今日指标越界次数：只统计带指标证据的事件，不含断连/RSSI 告警。 */
    @Query(
        "SELECT COUNT(*) FROM rule_events WHERE timestamp >= :from AND timestamp < :to " +
            "AND metric IS NOT NULL",
    )
    fun observeMetricViolationCountBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT * FROM rule_events WHERE timestamp >= :from AND timestamp < :to " +
        "ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestBetween(from: Long, to: Long): Flow<RuleEventEntity?>

    @Query("SELECT * FROM rule_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RuleEventEntity>

    @Query("DELETE FROM rule_events")
    suspend fun clear()
}

/** 场景及其有序动作；@Relation 由 Room 在事务内一次性取回动作列表与触发规则。 */
data class SceneWithActions(
    @Embedded val scene: SceneEntity,
    @Relation(parentColumn = "id", entityColumn = "sceneId")
    val actions: List<SceneActionEntity>,
    // 触发规则；空列表表示手动场景。规则由引擎判定，场景只负责执行动作。
    @Relation(parentColumn = "id", entityColumn = "sceneId")
    val rules: List<RuleEntity> = emptyList(),
)

@Dao
interface SceneDao {

    @Transaction
    @Query("SELECT * FROM scenes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SceneWithActions>>

    @Transaction
    @Query("SELECT * FROM scenes ORDER BY createdAt ASC")
    suspend fun getAll(): List<SceneWithActions>

    @Query("SELECT * FROM scenes WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): SceneEntity?

    @Insert
    suspend fun insertScene(scene: SceneEntity): Long

    @Insert
    suspend fun insertActions(actions: List<SceneActionEntity>)

    @Query("UPDATE scenes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE scenes SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM scene_actions WHERE sceneId = :sceneId")
    suspend fun deleteActions(sceneId: Long)

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- 场景触发规则：复用 rules 表，由 RuleEngine 消费，不另造平行配置。 ----

    @Insert
    suspend fun insertRule(rule: RuleEntity): Long

    @Query("SELECT * FROM rules WHERE sceneId = :sceneId LIMIT 1")
    suspend fun findRuleForScene(sceneId: Long): RuleEntity?

    @Query(
        "UPDATE rules SET type = :type, threshold = :threshold, metric = :metric, " +
            "comparator = :comparator, thresholdValue = :thresholdValue, " +
            "consecutive = :consecutive, intervalMinutes = :intervalMinutes, enabled = :enabled " +
            "WHERE sceneId = :sceneId",
    )
    suspend fun updateRuleForScene(
        sceneId: Long,
        type: String,
        threshold: Int,
        metric: String?,
        comparator: String,
        thresholdValue: Double,
        consecutive: Int,
        intervalMinutes: Int?,
        enabled: Boolean,
    )

    @Query("UPDATE rules SET enabled = :enabled WHERE sceneId = :sceneId")
    suspend fun setRulesEnabled(sceneId: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE sceneId = :sceneId")
    suspend fun deleteRules(sceneId: Long)

    /** 新建场景与其触发规则、动作在同一事务内落库。 */
    @Transaction
    suspend fun createSceneWithRule(
        scene: SceneEntity,
        rule: RuleEntity?,
        actions: List<SceneActionEntity>,
    ): Long {
        val sceneId = insertScene(scene)
        if (rule != null) insertRule(rule.copy(sceneId = sceneId))
        insertActions(actions.map { it.copy(sceneId = sceneId) })
        return sceneId
    }

    /** 更新场景：名称、启停、触发规则与动作整体替换。 */
    @Transaction
    suspend fun updateSceneWithRule(
        sceneId: Long,
        name: String,
        enabled: Boolean,
        rule: RuleEntity?,
        actions: List<SceneActionEntity>,
    ) {
        rename(sceneId, name)
        setEnabled(sceneId, enabled)
        deleteActions(sceneId)
        insertActions(actions.map { it.copy(sceneId = sceneId) })
        if (rule == null) {
            deleteRules(sceneId)
        } else {
            val existing = findRuleForScene(sceneId)
            if (existing == null) {
                insertRule(rule.copy(sceneId = sceneId, enabled = enabled))
            } else {
                updateRuleForScene(
                    sceneId = sceneId,
                    type = rule.type,
                    threshold = rule.threshold,
                    metric = rule.metric,
                    comparator = rule.comparator,
                    thresholdValue = rule.thresholdValue,
                    consecutive = rule.consecutive,
                    intervalMinutes = rule.intervalMinutes,
                    enabled = enabled,
                )
            }
        }
    }

    /** 删除场景及其触发规则（执行记录保留，用于历史回溯）。 */
    @Transaction
    suspend fun deleteSceneWithRule(sceneId: Long) {
        deleteActions(sceneId)
        deleteRules(sceneId)
        delete(sceneId)
    }
}

/** 场景执行记录：只增不改，清理由用户显式触发。 */
@Dao
interface SceneRunDao {

    @Insert
    suspend fun insert(run: SceneRunEntity): Long

    @Query("SELECT * FROM scene_runs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SceneRunEntity>>

    @Query("SELECT * FROM scene_runs ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<SceneRunEntity?>

    @Query("SELECT * FROM scene_runs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SceneRunEntity>

    @Query("DELETE FROM scene_runs")
    suspend fun clear()
}

/** 本地反馈草稿：pending 即「未发送」，等待未来接入远端后重试。 */
@Dao
interface FeedbackDao {

    @Insert
    suspend fun insert(draft: FeedbackDraftEntity): Long

    @Query(
        "SELECT * FROM feedback_drafts WHERE state = 'pending' " +
            "ORDER BY createdAt DESC, id DESC",
    )
    fun observePending(): Flow<List<FeedbackDraftEntity>>

    @Query("SELECT * FROM feedback_drafts WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): FeedbackDraftEntity?

    @Query("UPDATE feedback_drafts SET state = 'sent' WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query("DELETE FROM feedback_drafts WHERE id = :id")
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
