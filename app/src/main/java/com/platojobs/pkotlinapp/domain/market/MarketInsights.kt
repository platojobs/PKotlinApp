package com.platojobs.pkotlinapp.domain.market

import com.platojobs.pkotlinapp.domain.model.GattServiceSpec
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.SupportedMetric
import com.platojobs.pkotlinapp.domain.product.productCategoryOf
import com.platojobs.pkotlinapp.domain.product.supportedMetricsOf
import com.platojobs.pkotlinapp.domain.reading.ReadingUuids

/**
 * 市场页的「兼容性查询 / 选购指南」纯领域计算：
 * 所有结论都从现有 catalog（GATT 服务）与 [ReadingUuids] 推导，
 * 不维护第二份「支持机型/指标」真值，也不编造营销结论。
 */
enum class CompatibilityLimitation {
    /** NUS 是文本协议，设备必须按 `metric=value` 逐行输出才能解析。 */
    NUS_LINE_BASED,

    /** 目录声明了服务，但 App 没有实现对应测量特征。 */
    NO_PARSABLE_METRIC,

    /** 目录没有 GATT 服务清单，无法预判。 */
    NO_GATT_SERVICES,

    /** 目录没有固件版本，兼容性以实际固件为准。 */
    FIRMWARE_UNKNOWN,
}

data class CompatibilityProfile(
    val deviceTypeKey: String,
    val displayName: String,
    val vendor: String,
    val category: ProductCategory,
    val firmwareVersion: String,
    val metrics: List<SupportedMetric>,
    val services: List<GattServiceSpec>,
    val limitations: List<CompatibilityLimitation>,
)

enum class MarketScenario {
    HEALTH,
    ENVIRONMENT,
    MULTI_DEVICE,
}

/** 选购建议的依据：由目录中真实可解析指标与服务数量汇总。 */
data class ScenarioEvidence(
    val metrics: List<SupportedMetric>,
    val serviceCount: Int,
)

data class ScenarioRecommendation(
    val scenario: MarketScenario,
    val recommended: List<CompatibilityProfile>,
    val evidence: ScenarioEvidence,
)

/**
 * 兼容性档案：指标来自 [supportedMetricsOf]（内部使用 [ReadingUuids.supported]），
 * 服务清单直接展示 catalog 返回的 UUID，不做映射美化。
 */
fun compatibilityProfiles(models: List<ProductModel>): List<CompatibilityProfile> =
    models
        .filter { it.deviceTypeKey.isNotBlank() }
        .map { model ->
            val metrics = supportedMetricsOf(model.services.map { it.uuid })
            CompatibilityProfile(
                deviceTypeKey = model.deviceTypeKey,
                displayName = model.model.takeIf { it.isNotBlank() } ?: model.id,
                vendor = model.vendor,
                category = productCategoryOf(model.deviceTypeKey),
                firmwareVersion = model.firmwareVersion,
                metrics = metrics,
                services = model.services,
                limitations = limitationsOf(metrics, model.services, model.firmwareVersion),
            )
        }
        .sortedWith(compareBy({ it.category.ordinal }, { it.displayName }))

private fun limitationsOf(
    metrics: List<SupportedMetric>,
    services: List<GattServiceSpec>,
    firmwareVersion: String,
): List<CompatibilityLimitation> = buildList {
    if (services.isEmpty()) add(CompatibilityLimitation.NO_GATT_SERVICES)
    if (services.isNotEmpty() && metrics.isEmpty()) add(CompatibilityLimitation.NO_PARSABLE_METRIC)
    if (SupportedMetric.NUS in metrics) add(CompatibilityLimitation.NUS_LINE_BASED)
    if (firmwareVersion.isBlank()) add(CompatibilityLimitation.FIRMWARE_UNKNOWN)
}

/**
 * 按场景从真实能力筛选类型：
 * - 健康监测：App 能解析心率特征的类型；
 * - 环境采集：App 能解析温度特征的类型；
 * - 多设备组网：使用 NUS 文本通道的类型（可承载自定义指标）。
 * 没有匹配类型时返回空推荐，由界面如实说明，而不是硬凑一个型号。
 */
fun scenarioRecommendation(
    scenario: MarketScenario,
    profiles: List<CompatibilityProfile>,
): ScenarioRecommendation {
    val recommended = when (scenario) {
        MarketScenario.HEALTH -> profiles.filter { SupportedMetric.HEART_RATE in it.metrics }
        MarketScenario.ENVIRONMENT -> profiles.filter { SupportedMetric.TEMPERATURE in it.metrics }
        MarketScenario.MULTI_DEVICE -> profiles.filter { SupportedMetric.NUS in it.metrics }
    }.sortedWith(compareByDescending<CompatibilityProfile> { it.metrics.size }
        .thenBy { it.displayName })

    val metrics = recommended.flatMap { it.metrics }.distinct()
    val serviceCount = recommended.map { it.services.size }.maxOrNull() ?: 0
    return ScenarioRecommendation(
        scenario = scenario,
        recommended = recommended,
        evidence = ScenarioEvidence(metrics = metrics, serviceCount = serviceCount),
    )
}
