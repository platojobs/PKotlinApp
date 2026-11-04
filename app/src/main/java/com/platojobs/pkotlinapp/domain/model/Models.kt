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
    val name: String,
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
)

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
    val group: String = "",
    val lastSeenTimestamp: Long = 0L,
    val compatibility: Compatibility? = null,
    val fromLocalCache: Boolean = false,
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
