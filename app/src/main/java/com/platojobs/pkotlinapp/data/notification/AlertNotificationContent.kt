package com.platojobs.pkotlinapp.data.notification

import com.platojobs.pkotlinapp.domain.model.DeviceRule

/** 通知展示内容：与 Android 通知构建解耦，便于用 NotificationCompat 断言。 */
data class AlertNotificationContent(
    val id: Int,
    val channelId: String,
    val title: String,
    val text: String,
    val mac: String,
    val deviceName: String,
    val rssi: Int?,
    val triggeredAt: Long,
)

interface AlertContentBuilder {
    fun triggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        timestamp: Long,
    ): AlertNotificationContent

    fun recovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int,
    ): AlertNotificationContent

    /** 指标阈值告警；默认退化为 RSSI 版文案，具体实现由 [AndroidAlertContentBuilder] 覆盖。 */
    fun metricTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        metric: String,
        value: Double,
        unit: String,
        timestamp: Long,
    ): AlertNotificationContent = triggered(rule, mac, displayName, null, timestamp)

    fun metricRecovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        metric: String,
        value: Double,
        unit: String,
    ): AlertNotificationContent = recovered(rule, mac, displayName, rule.threshold)
}

/** 同一设备同一规则使用稳定通知 id：新告警覆盖旧告警，而不是堆一屏。 */
object AlertNotificationIds {
    fun forRule(ruleId: Long, mac: String): Int =
        "device-alert:$ruleId:${mac.trim().uppercase()}".hashCode()
}
