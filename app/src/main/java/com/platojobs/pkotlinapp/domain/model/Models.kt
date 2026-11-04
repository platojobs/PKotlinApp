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

/** 服务端返回的设备基础信息。 */
data class DeviceInfo(
    val mac: String,
    val vendor: String,
    val deviceType: String,
    val firmwareVersion: String,
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
