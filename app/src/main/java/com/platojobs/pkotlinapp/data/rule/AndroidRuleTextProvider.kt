package com.platojobs.pkotlinapp.data.rule

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.rule.RuleTextProvider

class AndroidRuleTextProvider(private val context: Context) : RuleTextProvider {

    override fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String =
        context.getString(R.string.rule_event_low_rssi, deviceName, rssi, threshold)

    override fun disconnected(deviceName: String): String =
        context.getString(R.string.rule_event_disconnected, deviceName)

    override fun bondLost(deviceName: String): String =
        context.getString(R.string.rule_event_bond_lost, deviceName)

    override fun scheduled(intervalMinutes: Int): String =
        context.getString(R.string.rule_event_scheduled, intervalMinutes)

    override fun metricThreshold(
        deviceName: String,
        metric: String,
        value: Double,
        unit: String,
        comparator: RuleComparator,
        threshold: Double,
    ): String {
        val unitText = MetricThreshold.displayUnit(metric, unit)
        return context.getString(
            R.string.rule_event_metric_threshold,
            deviceName,
            metricLabel(metric),
            MetricThreshold.formatValue(value) + unitText,
            comparator.symbol,
            MetricThreshold.formatValue(threshold) + unitText,
        )
    }

    private fun metricLabel(metric: String): String = context.getString(
        when (ReadingMetrics.displayAlias(metric)) {
            ReadingMetrics.TEMPERATURE -> R.string.readings_metric_temperature
            ReadingMetrics.HUMIDITY -> R.string.readings_metric_humidity
            ReadingMetrics.BATTERY -> R.string.readings_metric_battery
            ReadingMetrics.HEART_RATE -> R.string.readings_metric_heart_rate
            ReadingMetrics.CO2 -> R.string.readings_metric_co2
            ReadingMetrics.PRESSURE -> R.string.readings_metric_pressure
            else -> R.string.readings_metric_other
        },
    )
}
