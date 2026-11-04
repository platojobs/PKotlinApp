package com.platojobs.pkotlinapp.domain.model

enum class RuleType {
    LOW_RSSI,
    DISCONNECTED,
    BOND_LOST,
}

enum class SyncState {
    PENDING,
    SYNCED,
}

/** Room 中保存的设备历史记录。 */
data class Device(
    val mac: String,
    /**
     * 用户设置的别名（openapi `alias`）。扫描不会写入该字段，
     * 只有用户在详情页编辑别名时才会更新。
     */
    val name: String = "",
    val lastRssi: Int,
    val lastSeenTimestamp: Long,
    val id: String = "",
    val platform: String = PLATFORM_ANDROID,
    val externalId: String = "",
    val updatedAt: Long = lastSeenTimestamp,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.PENDING,
    // 云端 enrich 字段的本地缓存，仅用于断网降级展示与下次推送复用。
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: String? = null,
    // catalog 展示名与云端分组：离线时优先用展示名，分组按真实值展示。
    val deviceTypeName: String? = null,
    val group: String? = null,
    /**
     * 设备广播名（openapi `advertisedName`，BLE Local Name）。
     * 扫描只更新该字段；与用户别名 [name] 独立，互不覆盖。
     */
    val advertisedName: String? = null,
    /**
     * 用户是否显式编辑过别名（包括清空）。
     * 扫描路径永远不置位；置位后即使 [name] 为空也会作为显式清空上报。
     */
    val aliasDirty: Boolean = false,
)

/**
 * 列表/详情展示优先级：用户别名 > 广播名 > catalog 型号展示名。
 * 全部缺失时由 UI 回退「未知设备」。
 */
fun Device.displayName(): String? =
    name.takeIf { it.isNotBlank() }
        ?: advertisedName?.takeIf { it.isNotBlank() }
        ?: deviceTypeName?.takeIf { it.isNotBlank() }

const val PLATFORM_ANDROID = "android"

/** 设备唯一键：Android 使用规范化大写 MAC。 */
fun normalizeExternalId(mac: String): String = mac.trim().uppercase()

/** 一次 BLE 扫描回调中的设备快照。 */
data class ScannedDevice(
    val name: String?,
    val mac: String,
    val rssi: Int,
    val timestamp: Long,
)

/** 兼容性元数据（openapi `Compatibility`，保留扩展字段）。 */
data class Compatibility(
    val platforms: List<String> = emptyList(),
    val minAppVersion: String = "",
    val protocol: String = "",
    val features: List<String> = emptyList(),
    val deviceTypes: List<String> = emptyList(),
) {
    /** 展示用文案；空字段直接省略，不显示 "null" 或空串。 */
    fun displayLabel(): String {
        val parts = buildList {
            if (platforms.isNotEmpty()) add(platforms.joinToString("/"))
            if (protocol.isNotBlank()) add(protocol)
            if (features.isNotEmpty()) add(features.joinToString("/"))
            if (deviceTypes.isNotEmpty()) add("适配 " + deviceTypes.joinToString("/"))
        }
        return parts.joinToString(" · ")
    }

    /** 市场筛选维度：优先适配型号，其次平台与协议。 */
    fun filterTags(): List<String> = when {
        deviceTypes.isNotEmpty() -> deviceTypes
        platforms.isNotEmpty() -> platforms
        protocol.isNotBlank() -> listOf(protocol)
        else -> emptyList()
    }
}

/** 服务端返回的设备详情；enrich 字段均为 null 时表示未注册型号。 */
data class DeviceInfo(
    val mac: String,
    val vendor: String?,
    val deviceType: String?,
    val firmwareVersion: String?,
    val deviceTypeKey: String? = null,
    val alias: String = "",
    val advertisedName: String = "",
    val group: String = "",
    val lastSeenTimestamp: Long = 0L,
    val compatibility: Compatibility? = null,
    val fromLocalCache: Boolean = false,
)

/** 详情展示优先级：用户别名 > 广播名 > catalog 型号名。 */
fun DeviceInfo.displayName(): String? =
    alias.takeIf { it.isNotBlank() }
        ?: advertisedName.takeIf { it.isNotBlank() }
        ?: displayDeviceTypeName()

/**
 * 型号展示优先级：缓存/catalog 展示名 > 型号键（标注未识别）> 型号未知。
 * [deviceType] 只保存人类可读名称；[deviceTypeKey] 保存接口返回的型号键。
 */
fun DeviceInfo.displayDeviceTypeName(): String? =
    deviceType?.takeIf { it.isNotBlank() }

fun DeviceInfo.unrecognizedDeviceTypeKey(): String? =
    deviceTypeKey?.takeIf { it.isNotBlank() && deviceType.isNullOrBlank() }

/** 离线降级：只用 Room 已缓存字段构造详情，缺失字段保持 null，由 UI 统一兜底。 */
fun Device.toOfflineDeviceInfo(): DeviceInfo = DeviceInfo(
    mac = mac,
    vendor = vendor,
    deviceType = deviceTypeName,
    deviceTypeKey = deviceType,
    firmwareVersion = firmwareVersion,
    alias = name,
    advertisedName = advertisedName.orEmpty(),
    group = group.orEmpty(),
    lastSeenTimestamp = lastSeenTimestamp,
    compatibility = compatibility?.takeIf { it.isNotBlank() }?.let {
        Compatibility(protocol = it)
    },
    fromLocalCache = true,
)

data class GattServiceSpec(
    val uuid: String,
    val name: String,
    val characteristics: List<String>,
)

data class ProductModel(
    val id: String,
    val vendor: String,
    val model: String,
    val firmwareVersion: String,
    val note: String,
    val services: List<GattServiceSpec>,
)

data class MarketItem(
    val id: String,
    val title: String,
    val category: String,
    val version: String,
    val compatibility: String,
    val description: String,
    val vendor: String = "",
    /** 用于筛选的真实维度（兼容型号/平台），不编造分类。 */
    val compatibilityTags: List<String> = emptyList(),
)

data class DeviceRule(
    val id: Long,
    val type: RuleType,
    val threshold: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredTimestamp: Long,
)

data class RuleEvent(
    val id: Long,
    val ruleType: RuleType,
    val mac: String,
    val message: String,
    val timestamp: Long,
)

data class ScanLog(
    val id: Long,
    val mac: String,
    val name: String,
    val rssi: Int,
    val timestamp: Long,
)

fun ruleTypeOf(value: String): RuleType =
    runCatching { RuleType.valueOf(value) }.getOrDefault(RuleType.LOW_RSSI)
