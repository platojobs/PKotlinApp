package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 指标阈值规则的展示约束与格式化。
 * 单位遵循产品口径：温度 `℃`、百分比 `%`、心率 `bpm`；未知指标沿用上报单位。
 */
object MetricThreshold {
    /** 服务端契约允许 1–30；本机旧 UI 的建议值仍是 1–5，但不再截断合法的大值。 */
    const val MAX_CONSECUTIVE = 30

    fun sanitizeConsecutive(value: Int): Int = value.coerceIn(1, MAX_CONSECUTIVE)

    fun displayUnit(metric: String, rawUnit: String = ""): String =
        when (ReadingMetrics.displayAlias(metric)) {
            ReadingMetrics.TEMPERATURE -> "℃"
            ReadingMetrics.HUMIDITY, ReadingMetrics.BATTERY -> "%"
            ReadingMetrics.HEART_RATE -> "bpm"
            ReadingMetrics.CO2 -> "ppm"
            ReadingMetrics.PRESSURE -> "hPa"
            else -> rawUnit
        }

    /** 固定最多 1 位小数，去掉多余的 0；避免 31.0 这类噪声。 */
    fun formatValue(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val scaled = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
        return scaled.toPlainString()
    }

    /** 供纯函数与单测使用的数值比较入口。 */
    fun matches(value: Double, comparator: RuleComparator, threshold: Double): Boolean =
        comparator.matches(value, threshold)
}
