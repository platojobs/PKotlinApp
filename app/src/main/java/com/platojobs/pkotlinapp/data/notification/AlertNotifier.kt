package com.platojobs.pkotlinapp.data.notification

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.rule.AlertThrottle
import com.platojobs.pkotlinapp.domain.rule.RuleAlertSink

/**
 * 告警投递器：开关关闭 / 系统权限缺失时不投递；
 * 投递前经过节流，恢复通知只在真正告警过之后发一次。
 */
class AlertNotifier(
    private val throttle: AlertThrottle,
    private val isEnabled: () -> Boolean,
    private val canNotify: () -> Boolean,
    private val contentBuilder: AlertContentBuilder,
    private val poster: AlertPoster,
) : RuleAlertSink {

    override suspend fun onTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        timestamp: Long,
    ) {
        if (!isEnabled() || !canNotify()) return
        if (!throttle.allowTrigger(keyOf(rule, mac))) return
        poster.post(contentBuilder.triggered(rule, mac, displayName, rssi, timestamp))
    }

    override suspend fun onRecovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int,
    ) {
        // 即使开关关闭也消费恢复状态，避免下次开启时补发过期通知。
        if (!throttle.allowRecovery(keyOf(rule, mac))) return
        if (!isEnabled() || !canNotify()) return
        poster.post(contentBuilder.recovered(rule, mac, displayName, rssi))
    }

    override suspend fun onMetricTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        metric: String,
        value: Double,
        unit: String,
        timestamp: Long,
    ) {
        if (!isEnabled() || !canNotify()) return
        if (!throttle.allowTrigger(keyOf(rule, mac))) return
        poster.post(contentBuilder.metricTriggered(rule, mac, displayName, metric, value, unit, timestamp))
    }

    override suspend fun onMetricRecovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        metric: String,
        value: Double,
        unit: String,
    ) {
        if (!isEnabled() || !canNotify()) return
        if (!throttle.allowRecovery(keyOf(rule, mac))) return
        poster.post(contentBuilder.metricRecovered(rule, mac, displayName, metric, value, unit))
    }

    private fun keyOf(rule: DeviceRule, mac: String): String = "${rule.id}:$mac"
}
