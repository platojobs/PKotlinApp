package com.platojobs.pkotlinapp.domain.product

import com.platojobs.pkotlinapp.domain.reading.ReadingUuids

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

fun productCategoryOf(deviceTypeKey: String?): ProductCategory =
    when (deviceTypeKey?.trim()?.lowercase()) {
        "heart_rate_monitor", "smart_scale", "cycling_speed_cadence", "health" ->
            ProductCategory.HEALTH

        "environmental_sensor", "environment", "air_quality", "thermometer" ->
            ProductCategory.ENVIRONMENT

        "nordic_uart", "gateway", "relay", "hub", "uart" -> ProductCategory.GATEWAY
        else -> ProductCategory.OTHER
    }

/** App 真正能解析的指标类别；由 GATT 服务 → 测量特征 → [ReadingUuids.supported] 推导。 */
enum class SupportedMetric {
    HEART_RATE,
    BATTERY,
    TEMPERATURE,
    NUS,
}

/**
 * 把 catalog 返回的 GATT 服务 UUID 换成 App 能解析的指标。
 *
 * 判断依据是 [ReadingUuids.supported]：只有解析器实现了对应特征 UUID 才会列出，
 * 因此不会出现「后端说有、App 其实解析不了」的假文案。
 */
fun supportedMetricsOf(serviceUuids: List<String>): List<SupportedMetric> =
    serviceUuids
        .mapNotNull { SERVICE_TO_MEASUREMENT_UUID[ReadingUuids.normalize(it)] }
        .filter { it in ReadingUuids.supported }
        .distinct()
        .mapNotNull { uuid ->
            when (uuid) {
                ReadingUuids.HEART_RATE -> SupportedMetric.HEART_RATE
                ReadingUuids.BATTERY -> SupportedMetric.BATTERY
                ReadingUuids.TEMPERATURE -> SupportedMetric.TEMPERATURE
                ReadingUuids.NUS_TX -> SupportedMetric.NUS
                else -> null
            }
        }

/** GATT 服务 UUID → 该服务下 App 已实现解析的测量特征 UUID。 */
private val SERVICE_TO_MEASUREMENT_UUID = mapOf(
    "0000180d-0000-1000-8000-00805f9b34fb" to ReadingUuids.HEART_RATE,
    "0000180f-0000-1000-8000-00805f9b34fb" to ReadingUuids.BATTERY,
    "0000181a-0000-1000-8000-00805f9b34fb" to ReadingUuids.TEMPERATURE,
    "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to ReadingUuids.NUS_TX,
)
