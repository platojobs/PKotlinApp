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

@Entity(tableName = "scan_logs")
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val name: String,
    val rssi: Int,
    val timestamp: Long,
)
