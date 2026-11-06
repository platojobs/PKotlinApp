package com.platojobs.pkotlinapp.domain.market

import com.platojobs.pkotlinapp.domain.catalog.CatalogMetric
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.model.GattServiceSpec
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.productCategoryOf

/**
 * 市场页的「兼容性查询 / 选购指南」纯领域计算。
 *
 * 类型、GATT 服务、指标、限制、兼容性全部来自服务端目录；
 * 本机是否真的能解析某个指标由 [CatalogMetric.supportedByApp] 决定，
 * 不维护第二份「支持机型/指标」真值，也不编造营销结论。
 */
data class CompatibilityProfile(
    val deviceTypeKey: String,
    val displayName: String,
    val category: ProductCategory,
    /** 目录声明的全部指标；其中 [CatalogMetric.supportedByApp] 区分本机可解析与否。 */
    val metrics: List<CatalogMetric>,
    val services: List<GattServiceSpec>,
    /** 服务端目录声明的限制（原始文案），不再本地生成。 */
    val limitations: List<String>,
) {
    val supportedMetrics: List<CatalogMetric>
        get() = metrics.filter { it.supportedByApp }

    val unsupportedMetrics: List<CatalogMetric>
        get() = metrics.filterNot { it.supportedByApp }
}

enum class MarketScenario {
    HEALTH,
    ENVIRONMENT,
    MULTI_DEVICE,
}

/** 选购建议的依据：由目录中真实可解析指标与服务数量汇总。 */
data class ScenarioEvidence(
    val metrics: List<CatalogMetric>,
    val serviceCount: Int,
)

data class ScenarioRecommendation(
    val scenario: MarketScenario,
    val recommended: List<CompatibilityProfile>,
    val evidence: ScenarioEvidence,
)

/** 兼容性档案：直接消费服务端目录条目，不经过本地型号映射。 */
fun compatibilityProfiles(items: List<DeviceTypeCatalogItem>): List<CompatibilityProfile> =
    items
        .filter { it.type.isNotBlank() }
        .map { item ->
            CompatibilityProfile(
                deviceTypeKey = item.type,
                displayName = item.displayName.ifBlank { item.type },
                category = productCategoryOf(item.type),
                metrics = item.metrics,
                services = item.services.map { service ->
                    GattServiceSpec(
                        uuid = service.uuid,
                        name = service.name,
                        characteristics = emptyList(),
                    )
                },
                limitations = item.limitations,
            )
        }
        .sortedWith(compareBy({ it.category.ordinal }, { it.displayName }))

/**
 * 按场景从真实能力筛选类型：
 * - 健康监测：目录声明心率且本机解析器支持；
 * - 环境采集：目录声明温度且本机解析器支持；
 * - 多设备组网：使用 NUS 文本通道的类型。
 * 没有匹配类型时返回空推荐，由界面如实说明，而不是硬凑一个型号。
 */
fun scenarioRecommendation(
    scenario: MarketScenario,
    profiles: List<CompatibilityProfile>,
): ScenarioRecommendation {
    val recommended = when (scenario) {
        MarketScenario.HEALTH -> profiles.filter { profile ->
            profile.supportedMetrics.any { it.key == METRIC_HEART_RATE }
        }

        MarketScenario.ENVIRONMENT -> profiles.filter { profile ->
            profile.supportedMetrics.any { it.key == METRIC_TEMPERATURE }
        }

        MarketScenario.MULTI_DEVICE -> profiles.filter { profile ->
            profile.services.any { it.uuid.trim().lowercase() == NUS_SERVICE_UUID }
        }
    }.sortedWith(
        compareByDescending<CompatibilityProfile> { it.supportedMetrics.size }
            .thenBy { it.displayName },
    )

    val metrics = recommended.flatMap { it.supportedMetrics }.distinct()
    val serviceCount = recommended.maxOfOrNull { it.services.size } ?: 0
    return ScenarioRecommendation(
        scenario = scenario,
        recommended = recommended,
        evidence = ScenarioEvidence(metrics = metrics, serviceCount = serviceCount),
    )
}

private const val METRIC_HEART_RATE = "heart_rate"
private const val METRIC_TEMPERATURE = "temperature_celsius"
private const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
