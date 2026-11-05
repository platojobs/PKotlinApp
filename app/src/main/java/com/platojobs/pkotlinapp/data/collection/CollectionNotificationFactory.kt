package com.platojobs.pkotlinapp.data.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity

/** 常驻采集通知：渠道「数据采集」，含设备名、已采集条数与停止动作。 */
object CollectionNotificationFactory {

    const val CHANNEL_ID = "reading_collection"
    const val NOTIFICATION_ID = 2001
    const val ACTION_STOP = "com.platojobs.pkotlinapp.action.STOP_COLLECTION"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.collection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.collection_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, status: CollectionStatus): Notification {
        ensureChannel(context)
        val stopIntent = Intent(context, ReadingCollectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPending = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            DeviceDetailActivity.createIntent(
                context,
                status.mac,
                status.deviceName,
                Int.MIN_VALUE,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(context.getString(R.string.collection_notification_title))
            .setContentText(
                context.getString(
                    R.string.collection_notification_text,
                    status.deviceName.ifBlank { status.mac },
                    status.count,
                ),
            )
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_notification_alert,
                context.getString(R.string.collection_notification_stop),
                stopPending,
            )
            .build()
    }

    private const val REQUEST_STOP = 21
    private const val REQUEST_OPEN = 22
}
