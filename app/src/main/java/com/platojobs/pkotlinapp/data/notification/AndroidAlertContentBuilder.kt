package com.platojobs.pkotlinapp.data.notification

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知文案：只含设备名、指标、当前值、阈值与触发时间，
 * 绝不放入 token、账号或其它敏感信息。
 */
class AndroidAlertContentBuilder(
    private val context: Context,
) : AlertContentBuilder {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun triggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        timestamp: Long,
    ): AlertNotificationContent {
        val name = displayName.ifBlank { context.getString(R.string.device_unknown) }
        val time = timeFormat.format(Date(timestamp))
        val text = when (rule.type) {
            RuleType.LOW_RSSI -> context.getString(
                R.string.alert_notify_triggered_rssi,
                name,
                rssi ?: rule.threshold,
                rule.threshold,
                time,
            )

            RuleType.DISCONNECTED -> context.getString(
                R.string.alert_notify_triggered_disconnected,
                name,
                time,
            )

            RuleType.BOND_LOST -> context.getString(
                R.string.alert_notify_triggered_bond_lost,
                name,
                time,
            )
        }
        return AlertNotificationContent(
            id = AlertNotificationIds.forRule(rule.id, mac),
            channelId = AlertNotificationFactory.CHANNEL_ID,
            title = context.getString(R.string.alert_notify_title),
            text = text,
            mac = mac,
            deviceName = name,
            rssi = rssi,
            triggeredAt = timestamp,
        )
    }

    override fun recovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int,
    ): AlertNotificationContent {
        val name = displayName.ifBlank { context.getString(R.string.device_unknown) }
        val time = timeFormat.format(Date(System.currentTimeMillis()))
        return AlertNotificationContent(
            id = AlertNotificationIds.forRule(rule.id, mac),
            channelId = AlertNotificationFactory.CHANNEL_ID,
            title = context.getString(R.string.alert_notify_recovered_title),
            text = context.getString(
                R.string.alert_notify_recovered_rssi,
                name,
                rssi,
                rule.threshold,
                time,
            ),
            mac = mac,
            deviceName = name,
            rssi = rssi,
            triggeredAt = System.currentTimeMillis(),
        )
    }
}
