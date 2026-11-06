package com.platojobs.pkotlinapp.data.scene

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.collection.CollectionLauncher
import com.platojobs.pkotlinapp.data.collection.CollectionSession
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.scene.SceneRuntime

/**
 * 场景运行时的真实实现：连接/断开走 GATT，采集走前台服务，
 * 静音写偏好设置（投递器实时读取），同步走 WorkManager。
 */
class SceneRuntimeImpl(
    private val context: Context,
    private val ble: BleConnectionManager,
    private val collectionSession: CollectionSession,
    private val settings: AppSettings,
    private val syncScheduler: SyncScheduler,
    private val devices: DeviceRepository,
) : SceneRuntime {

    override suspend fun connectDevice(mac: String): Boolean = ble.connect(mac)

    override suspend fun disconnectDevice(mac: String): Boolean {
        val connectedMac = connectedMac() ?: return false
        if (!connectedMac.equals(mac, ignoreCase = true)) return false
        ble.disconnect(mac)
        return true
    }

    override suspend fun connectedMac(): String? =
        (ble.state.value as? BleConnectionState.Connected)?.mac

    override fun collectionActive(): Boolean = collectionSession.status.value.active

    override fun activeCollectionMac(): String? =
        collectionSession.status.value.mac.takeIf { collectionSession.status.value.active }

    override suspend fun startCollection(mac: String): Boolean {
        val name = devices.getDevice(mac)?.name?.takeIf { it.isNotBlank() } ?: mac
        val result = CollectionLauncher.start(context, mac, name)
        if (result.started) {
            collectionSession.start(mac, name)
            settings.backgroundCollectionEnabled = true
        }
        return result.started
    }

    override fun stopCollection(): Boolean {
        if (!collectionSession.status.value.active) return false
        CollectionLauncher.stop(context)
        collectionSession.stop()
        settings.backgroundCollectionEnabled = false
        return true
    }

    override fun muteAlerts(minutes: Int) {
        settings.muteAlertsFor(minutes)
    }

    override fun syncNow() {
        syncScheduler.scheduleNow()
    }

    override suspend fun notify(title: String, body: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            ensureNotificationChannel()
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context)
                .notify(SCENE_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    override suspend fun markDevice(mac: String): Boolean {
        if (devices.getDevice(mac) == null) return false
        devices.markDevice(mac, System.currentTimeMillis())
        return true
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.scene_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.scene_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "scene_actions"
        const val SCENE_NOTIFICATION_ID = 3200
    }
}
