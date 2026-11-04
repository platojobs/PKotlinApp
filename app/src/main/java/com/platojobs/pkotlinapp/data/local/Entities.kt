package com.platojobs.pkotlinapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
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
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val threshold: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredTimestamp: Long,
)

@Entity(tableName = "rule_events")
data class RuleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleType: String,
    val mac: String,
    val message: String,
    val timestamp: Long,
)

@Entity(tableName = "scan_logs")
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val name: String,
    val rssi: Int,
    val timestamp: Long,
)
