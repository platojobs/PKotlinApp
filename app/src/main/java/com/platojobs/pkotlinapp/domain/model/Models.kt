package com.platojobs.pkotlinapp.domain.model

import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.SupportedMetric

enum class RuleType {
    LOW_RSSI,
    DISCONNECTED,
    BOND_LOST,
    /** 指标读数阈值规则：指标 + 比较符 + 阈值 + 连续 N 条满足。 */
    METRIC_THRESHOLD,
}

/** 指标阈值比较符；[symbol] 用于展示与存储，[matches] 为纯判定。 */
enum class RuleComparator(val symbol: String) {
    GREATER(">"),
    LESS("<"),
    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<="),
    ;

    fun matches(value: Double, threshold: Double): Boolean = when (this) {
        GREATER -> value > threshold
        LESS -> value < threshold
        GREATER_OR_EQUAL -> value >= threshold
        LESS_OR_EQUAL -> value <= threshold
    }

    companion object {
        fun parse(raw: String?): RuleComparator =
            entries.firstOrNull { it.name == raw || it.symbol == raw } ?: GREATER
    }
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
    /** 首次添加时间，只写一次；用于「按添加时间」排序。 */
    val createdAt: Long = lastSeenTimestamp,
    /** 本地房间归属；null 表示未分配。 */
    val roomId: Long? = null,
    /** 设备所属家庭（households.id）；null 表示旧数据，首次家庭同步后回填。 */
    val householdId: Long? = null,
    /** 服务端房间 UUID；null 表示未分配或仅本地房间。 */
    val remoteRoomId: String? = null,
    /** 是否为其他家庭成员共享的设备：可见但只读，不参与本机 push。 */
    val shared: Boolean = false,
    /** 房间变更待上报：离线/失败时保留，下次同步重试。 */
    val pendingRoomPatch: Boolean = false,
    val pendingRoomLocalId: Long? = null,
    val pendingHouseholdRemoteId: String? = null,
) {
    /** 共享设备不允许本机修改房间归属（服务端会对非 owner 返回 404）。 */
    val canEditRoom: Boolean get() = !shared
}

/** 本机房间：remoteId 为空表示仅存本机、尚未同步。 */
data class Room(
    val id: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val remoteId: String? = null,
    val householdId: Long? = null,
) {
    val synced: Boolean get() = remoteId != null
}

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
    /** 设备类型说明，来自 catalog 真实返回。 */
    val note: String,
    val services: List<GattServiceSpec>,
    /** 真实 `deviceType` 键，用于分类与「我的设备」匹配。 */
    val deviceTypeKey: String = id,
    val compatibility: Compatibility? = null,
    /** 由 GATT 服务 UUID + 解析器支持集合推导，不写死。 */
    val supportedMetrics: List<SupportedMetric> = emptyList(),
    val category: ProductCategory = ProductCategory.OTHER,
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
    /** RSSI 规则的整数阈值；指标规则请使用 [thresholdValue]。 */
    val threshold: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredTimestamp: Long,
    /** 指标规则的目标指标（[com.platojobs.pkotlinapp.domain.reading.ReadingMetrics] 归一化值）。 */
    val metric: String? = null,
    val comparator: RuleComparator = RuleComparator.GREATER,
    val thresholdValue: Double = 0.0,
    /** 连续满足的读数条数（1–5），用于防抖。 */
    val consecutive: Int = 1,
) {
    val isMetricRule: Boolean get() = type == RuleType.METRIC_THRESHOLD
}

/** 一条真实命中的规则事件；指标规则额外携带触发值、阈值与单位。 */
data class RuleEvent(
    val id: Long,
    val ruleType: RuleType,
    val mac: String,
    val message: String,
    val timestamp: Long,
    val metric: String? = null,
    val value: Double? = null,
    val thresholdValue: Double? = null,
    val unit: String? = null,
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
