package com.platojobs.pkotlinapp.data.remote

/** `GET /api/v1/devices/{mac}`：公共设备元数据。 */
data class PublicDeviceDto(
    val mac: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val deviceType: String? = null,
    val firmwareVersion: String? = null,
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
    val releaseNotes: String? = null,
    val releasedAt: String? = null,
)

data class AccessoryDto(
    val id: String? = null,
    val deviceType: String? = null,
    val name: String? = null,
    val sku: String? = null,
    val description: String? = null,
)

data class GattServiceDto(
    val uuid: String? = null,
    val name: String? = null,
)

/** `GET /api/v1/my/devices/{platform}/{externalId}`：用户私有设备。 */
data class UserDeviceDto(
    val id: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val alias: String? = null,
)
