package com.platojobs.pkotlinapp.data.remote

/** openapi `Compatibility`：保留扩展字段，`deviceTypes` 为配件/固件额外维度。 */
data class CompatibilityDto(
    val platforms: List<String>? = null,
    val minAppVersion: String? = null,
    val protocol: String? = null,
    val features: List<String>? = null,
    val deviceTypes: List<String>? = null,
)

internal fun CompatibilityDto.toDomain(): com.platojobs.pkotlinapp.domain.model.Compatibility =
    com.platojobs.pkotlinapp.domain.model.Compatibility(
        platforms = platforms.orEmpty().filter { it.isNotBlank() },
        minAppVersion = minAppVersion.orEmpty(),
        protocol = protocol.orEmpty(),
        features = features.orEmpty().filter { it.isNotBlank() },
        deviceTypes = deviceTypes.orEmpty().filter { it.isNotBlank() },
    )

/** 展平后的兼容性文案，用于本地缓存与列表展示。 */
internal fun CompatibilityDto.displayLabel(): String = toDomain().displayLabel()

/** `GET /api/v1/devices/{mac}`：公共设备元数据。 */
data class PublicDeviceDto(
    val mac: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val deviceType: String? = null,
    val firmwareVersion: String? = null,
    val gattServices: List<GattServiceDto>? = null,
    val compatibility: CompatibilityDto? = null,
)

/** `GET /api/v1/device-types` 的响应。 */
data class DeviceTypesResponse(
    val items: List<DeviceTypeDto>? = null,
)

data class DeviceTypeDto(
    val type: String? = null,
    val name: String? = null,
    val vendor: String? = null,
    val description: String? = null,
    val gattServices: List<GattServiceDto>? = null,
    val compatibility: CompatibilityDto? = null,
)

/**
 * `GET /api/v1/device-types/{type}/catalog` 的响应：
 * 一次返回型号元数据、固件列表与配件列表。
 */
data class DeviceCatalogDto(
    val deviceType: DeviceTypeDto? = null,
    val firmwares: List<FirmwareDto>? = null,
    val accessories: List<AccessoryDto>? = null,
)

data class FirmwareDto(
    val id: String? = null,
    val deviceType: String? = null,
    val version: String? = null,
    val vendor: String? = null,
    val releaseNotes: String? = null,
    val releasedAt: String? = null,
    val compatibility: CompatibilityDto? = null,
)

data class AccessoryDto(
    val id: String? = null,
    val deviceType: String? = null,
    val name: String? = null,
    val sku: String? = null,
    val vendor: String? = null,
    val description: String? = null,
    val compatibility: CompatibilityDto? = null,
)

data class GattServiceDto(
    val uuid: String? = null,
    val name: String? = null,
)

/**
 * `GET /api/v1/catalog/device-types`（openapi 0.14.0）：
 * 服务端设备类型目录，公开只读，ETag/Last-Modified 支持 304。
 */
data class DeviceTypeCatalogDto(
    val version: Long? = null,
    val generatedAt: String? = null,
    val items: List<CatalogDeviceTypeDto>? = null,
)

data class CatalogDeviceTypeDto(
    val type: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val gattServices: List<GattServiceDto>? = null,
    val metrics: List<CatalogMetricDto>? = null,
    val limitations: List<String>? = null,
    val compatibility: CompatibilityDto? = null,
    val updatedAt: String? = null,
)

/** 目录声明的指标：key / unit / 来源 GATT 特征（不是服务）UUID。 */
data class CatalogMetricDto(
    val key: String? = null,
    val unit: String? = null,
    val characteristicUuid: String? = null,
)

/** 目录条目缓存载荷；与条目分开存放，便于整表替换时一次序列化。 */
data class CatalogItemPayloadDto(
    val gattServices: List<GattServiceDto> = emptyList(),
    val metrics: List<CatalogMetricDto> = emptyList(),
    val limitations: List<String> = emptyList(),
    val compatibility: CompatibilityDto? = null,
)

/**
 * `GET /api/v1/my/devices/{platform}/{externalId}`：用户私有设备。
 * 0.3.2 起带 enrich 字段（deviceType/vendor/firmwareVersion/compatibility），均可为 null。
 */
data class UserDeviceDto(
    val id: String? = null,
    val clientId: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val alias: String? = null,
    // openapi 0.3.3：设备广播名，与用户别名独立。
    val advertisedName: String? = null,
    val group: String? = null,
    val scanRecords: List<SyncScanRecordDto>? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val serverSeq: Long? = null,
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: CompatibilityDto? = null,
    // openapi 0.21.0：服务端在响应时实时推导的在线三态（unknown/online/offline）。
    val presence: String? = null,
    // 服务端最后一次接受读数上报的接收时间。三态：
    // 属性缺失=老后端，保持本地值；显式 JSON null=从未上报，清空本地值。
    val lastSeenAt: NullableReported? = null,
)
