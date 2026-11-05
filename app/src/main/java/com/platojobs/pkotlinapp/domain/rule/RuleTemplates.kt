package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics

/** 一条可一键落库的推荐规则；只由设备真实出现过的指标生成。 */
data class RuleTemplate(
    val deviceMac: String,
    val deviceName: String,
    val metric: String,
    val comparator: RuleComparator,
    val threshold: Double,
    val consecutive: Int = 1,
) {
    val unit: String get() = MetricThreshold.displayUnit(metric)
}

/**
 * 基于真实指标生成推荐模板：
 * - 只对白名单指标生成，未知指标（other）不生成假模板；
 * - 已存在同指标 + 同比较符 + 同阈值的规则时不重复推荐；
 * - 阈值固定为产品定义的常用值，不猜测用户数据分布。
 */
object RuleTemplates {

    private data class Spec(
        val metric: String,
        val comparator: RuleComparator,
        val threshold: Double,
    )

    private val specs = listOf(
        Spec(ReadingMetrics.TEMPERATURE, RuleComparator.GREATER, 30.0),
        Spec(ReadingMetrics.HUMIDITY, RuleComparator.GREATER, 70.0),
        Spec(ReadingMetrics.BATTERY, RuleComparator.LESS, 20.0),
        Spec(ReadingMetrics.HEART_RATE, RuleComparator.GREATER, 120.0),
        Spec(ReadingMetrics.CO2, RuleComparator.GREATER, 1000.0),
    )

    fun generate(
        deviceMac: String,
        deviceName: String,
        realMetrics: Collection<String>,
        existingRules: List<DeviceRule> = emptyList(),
    ): List<RuleTemplate> {
        val normalized = realMetrics.map(ReadingMetrics::normalize).toSet()
        val existing = existingRules
            .filter { it.type == RuleType.METRIC_THRESHOLD }
            .map { Triple(it.metric ?: "", it.comparator, it.thresholdValue) }
            .toSet()
        return specs
            .filter { it.metric in normalized }
            .filterNot { Triple(it.metric, it.comparator, it.threshold) in existing }
            .map {
                RuleTemplate(
                    deviceMac = deviceMac,
                    deviceName = deviceName,
                    metric = it.metric,
                    comparator = it.comparator,
                    threshold = it.threshold,
                )
            }
    }
}
