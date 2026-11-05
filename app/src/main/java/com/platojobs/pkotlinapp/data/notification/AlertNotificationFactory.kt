package com.platojobs.pkotlinapp.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity

/** 通知构建：单独渠道「设备告警」，点击进入对应设备详情。 */
object AlertNotificationFactory {

    const val CHANNEL_ID = "device_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.alert_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        content: AlertNotificationContent,
    ): NotificationCompat.Builder {
        ensureChannel(context)
        val intent = DeviceDetailActivity.createIntent(
            context,
            content.mac,
            content.deviceName,
            content.rssi ?: Int.MIN_VALUE,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            content.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, content.channelId)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }
}
