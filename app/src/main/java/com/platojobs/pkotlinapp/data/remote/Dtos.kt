package com.platojobs.pkotlinapp.data.remote

data class DeviceInfoDto(
    val mac: String? = null,
    val vendor: String? = null,
    val deviceType: String? = null,
    val firmwareVersion: String? = null,
)

data class ProductsResponse(
    val products: List<ProductDto>? = null,
)

data class ProductDto(
    val id: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val firmwareVersion: String? = null,
    val note: String? = null,
    val services: List<GattServiceDto>? = null,
)

data class GattServiceDto(
    val uuid: String? = null,
    val name: String? = null,
    val characteristics: List<String>? = null,
)

data class MarketResponse(
    val marketItems: List<MarketItemDto>? = null,
)

data class MarketItemDto(
    val id: String? = null,
    val title: String? = null,
    val category: String? = null,
    val version: String? = null,
    val compatibility: String? = null,
    val description: String? = null,
)
