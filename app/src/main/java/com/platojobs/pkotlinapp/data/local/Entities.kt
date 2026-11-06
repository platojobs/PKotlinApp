package com.platojobs.pkotlinapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    val name: String,
    val lastRssi: Int,
    val lastSeenTimestamp: Long,
    @ColumnInfo(defaultValue = "''") val id: String,
    @ColumnInfo(defaultValue = "'android'") val platform: String,
    @ColumnInfo(defaultValue = "''") val externalId: String,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "'pending'") val syncState: String,
    // openapi 0.3.2 enrich 字段的本地缓存（可为 null，未注册型号保持 null）。
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: String? = null,
    // catalog 返回的人类可读型号名，用于离线优先展示；null 表示只有型号键。
    val deviceTypeName: String? = null,
    // 云端分组（列名避开 SQL 关键字 group）；null 表示尚未拿到，"" 表示已显式清空。
    @ColumnInfo(name = "deviceGroup") val group: String? = null,
    // 设备广播名（BLE Local Name）；扫描只写该列，与用户别名 name 独立。
    val advertisedName: String? = null,
    // 用户是否显式编辑过别名（含清空）。扫描永远不置位；置位后空别名也要上报。
    @ColumnInfo(defaultValue = "0") val aliasDirty: Boolean = false,
    // 首次添加时间（只写一次），供「按添加时间」排序；历史数据迁移时回填 lastSeenTimestamp。
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    // 本地房间归属；null 表示未分配。
    val roomId: Long? = null,
    // 设备所属家庭（households.id）；null 表示旧数据，首次家庭同步后回填。
    val householdId: Long? = null,
    // 服务端房间 UUID；与本地 roomId 并存，避免多一次映射表 join。
    val remoteRoomId: String? = null,
    // 房间变更待上报：1 表示下次同步需要 PATCH /my/devices。
    @ColumnInfo(defaultValue = "0") val pendingRoomPatch: Boolean = false,
    // 待上报的目标本地房间 id；null 且 pendingRoomPatch=1 表示清空房间归属。
    val pendingRoomLocalId: Long? = null,
    // 待上报的家庭 UUID；null 表示不带 householdId 字段。
    val pendingHouseholdRemoteId: String? = null,
    // 是否为其他家庭成员共享的设备（只读，不参与 push）。
    @ColumnInfo(defaultValue = "0") val shared: Boolean = false,
    // 场景「标记设备」动作写入的时间戳；null 表示未被标记。
    val markedAt: Long? = null,
)

/** 本地家庭：remoteId 为服务端 UUID；id 为本地自增，供房间/设备外键引用。 */
@Entity(
    tableName = "households",
    indices = [Index(value = ["remoteId"], unique = true)],
)
data class HouseholdEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val isPersonal: Boolean,
    val role: String,
    @ColumnInfo(defaultValue = "0") val memberCount: Int,
    @ColumnInfo(defaultValue = "0") val deviceCount: Int,
    /** 服务端统计的房间数；本地缓存不参与计算。 */
    @ColumnInfo(defaultValue = "0") val roomCount: Int,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
)

/** 本地房间：remoteId 为空表示仅存本机、尚未同步。 */
@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int,
    val createdAt: Long,
    // 服务端房间 UUID；null 表示本机新建、尚未上传。
    val remoteId: String? = null,
    // 所属家庭（households.id）；null 表示 v12 迁移前的旧房间，首次家庭同步时归入当前家庭。
    val householdId: Long? = null,
    // 本地有未上传的改名/排序：结构变更到达时以本地值为准，服务端值先落到暂存列。
    @ColumnInfo(defaultValue = "0") val pendingEdit: Boolean = false,
    // 暂存的服务端名称/顺序，等本地 pending 合并后再生效；null 表示没有待合并值。
    val serverName: String? = null,
    val serverSortOrder: Int? = null,
)

/**
 * 设备读数：id 为客户端 UUID（服务端按 id 幂等）。
 * 外键指向 devices.mac；7 天保留策略由仓库层清理。
 */
@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["mac"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("deviceId"),
        Index("syncState"),
        Index(value = ["deviceId", "metric", "recordedAt"]),
    ],
)
data class ReadingEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val recordedAt: Long,
    @ColumnInfo(defaultValue = "'pending'") val syncState: String,
    val rawSnapshot: String? = null,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    /** RSSI 规则的整数阈值；旧规则迁移后仍按该列生效。 */
    val threshold: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredTimestamp: Long,
    // 指标阈值规则字段；旧数据保持 null / 默认值，仍走 RSSI 分支。
    val metric: String? = null,
    @ColumnInfo(defaultValue = "'>'") val comparator: String = ">",
    @ColumnInfo(defaultValue = "0") val thresholdValue: Double = 0.0,
    @ColumnInfo(defaultValue = "1") val consecutive: Int = 1,
    // 绑定到场景的触发规则：null 表示独立的自动化规则；非 null 时由场景执行器消费。
    val sceneId: Long? = null,
    // 定时规则的执行间隔（分钟）；仅 SCHEDULE 规则使用。
    val intervalMinutes: Int? = null,
)

@Entity(tableName = "rule_events")
data class RuleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleType: String,
    val mac: String,
    val message: String,
    val timestamp: Long,
    // 指标规则的命中证据：触发值、阈值、单位；旧事件保持 null。
    val metric: String? = null,
    val value: Double? = null,
    val thresholdValue: Double? = null,
    val unit: String? = null,
)

/**
 * 本地场景：有序动作组合，仅保存在本机，不参与云端同步。
 */
@Entity(tableName = "scenes")
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    // 停用后自动触发不再执行；手动运行仍可用。
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
)

/**
 * 场景执行记录：由引擎或手动触发真实写入，界面只读展示。
 * sceneId 不带外键，场景删除后历史记录仍然保留（sceneName 为当时的快照）。
 */
@Entity(tableName = "scene_runs", indices = [Index("timestamp")])
data class SceneRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sceneId: Long,
    val sceneName: String,
    val timestamp: Long,
    /** 触发来源：RuleType 名称或 MANUAL。 */
    val triggerType: String,
    /** 触发值快照（如指标=33.0℃、RSSI=-85、间隔=30 分钟）。 */
    val triggerValue: String,
    val successCount: Int,
    val failureCount: Int,
    /** 失败汇总原因；全部成功时为 null。 */
    val failureReason: String? = null,
    /** 逐动作结果（换行分隔），失败原因包含在内。 */
    val detail: String,
)

@Entity(
    tableName = "scene_actions",
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sceneId")],
)
data class SceneActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sceneId: Long,
    /** 动作在场景内的顺序（0 起，升序执行）。 */
    val position: Int,
    /** SceneActionType.name。 */
    val type: String,
    /** 连接/断开等设备相关动作的目标 MAC。 */
    val deviceMac: String? = null,
    /** 静音告警的分钟数（10/30/60）。 */
    val muteMinutes: Int? = null,
)

/**
 * 本地意见反馈草稿：尚未接入反馈服务端时先落库排队。
 * state=pending 表示「未发送」，sent 仅在未来接入远端后使用。
 */
@Entity(tableName = "feedback_drafts", indices = [Index("state")])
data class FeedbackDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.platojobs.pkotlinapp.domain.feedback.FeedbackCategory] 名称。 */
    val category: String,
    val description: String,
    val contact: String? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'pending'") val state: String = "pending",
)

@Entity(tableName = "scan_logs")
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val name: String,
    val rssi: Int,
    val timestamp: Long,
)
