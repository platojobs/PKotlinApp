package com.platojobs.pkotlinapp.domain.product

/** 产品目录分类：由真实 `deviceType` 键归类，未知键落到 [OTHER]，不编造分类。 */
enum class ProductCategory {
    HEALTH,
    ENVIRONMENT,
    GATEWAY,
    OTHER,
    ;

    companion object {
        /** 分类顺序即导航顺序。 */
        val navigable = listOf(HEALTH, ENVIRONMENT, GATEWAY, OTHER)
    }
}

/**
 * 展示分组（不是目录真值）：服务端目录只提供类型键与元数据，
 * 分类纯属导航需要；未知类型如实落到 [ProductCategory.OTHER]。
 */
fun productCategoryOf(deviceTypeKey: String?): ProductCategory =
    when (deviceTypeKey?.trim()?.lowercase()) {
        "heart_rate_monitor", "smart_scale", "cycling_speed_cadence", "health" ->
            ProductCategory.HEALTH

        "environmental_sensor", "environment", "air_quality", "thermometer" ->
            ProductCategory.ENVIRONMENT

        "nordic_uart", "gateway", "relay", "hub", "uart" -> ProductCategory.GATEWAY
        else -> ProductCategory.OTHER
    }
