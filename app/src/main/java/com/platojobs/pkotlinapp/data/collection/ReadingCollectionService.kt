package com.platojobs.pkotlinapp.data.collection

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台采集前台服务：连接设备后保持 notify 采集，通知常驻并显示已采集条数。
 *
 * - 只能由界面（连接成功/开关）通过 [CollectionLauncher] 启动，符合 Android 12+
 *   「不得从后台静默启动前台服务」的限制；
 * - Android 14+ 使用 `foregroundServiceType=connectedDevice`；
 * - 断开/绑定丢失/Idle 或用户点通知「停止」时自行停止并移除通知；
 * - 启动前台失败（如权限被系统拒绝）时回滚会话状态并停止，不崩溃。
 */
class ReadingCollectionService : Service() {

    private val container get() = (application as PKotlinApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CollectionNotificationFactory.ACTION_STOP) {
            stopCollection()
            return START_NOT_STICKY
        }
        val session = container.collectionSession
        val current = session.status.value
        val mac = intent?.getStringExtra(EXTRA_MAC) ?: current.mac
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: current.deviceName
        if (mac.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        session.start(mac, name.orEmpty())

        val result = runCatching {
            ServiceCompat.startForeground(
                this,
                CollectionNotificationFactory.NOTIFICATION_ID,
                CollectionNotificationFactory.build(this, session.status.value),
                foregroundServiceType(),
            )
        }
        if (result.isFailure) {
            // 权限缺失/系统拒绝：降级为不采集，避免崩溃或常驻空服务。
            session.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundStarted = true

        if (!observing) {
            observing = true
            observeSession()
            observeConnection()
        }
        return START_NOT_STICKY
    }

    private fun observeSession() {
        scope.launch {
            container.collectionSession.status.collect { status ->
                if (!foregroundStarted) return@collect
                if (!status.active) return@collect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@ReadingCollectionService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                NotificationManagerCompat.from(this@ReadingCollectionService).notify(
                    CollectionNotificationFactory.NOTIFICATION_ID,
                    CollectionNotificationFactory.build(this@ReadingCollectionService, status),
                )
            }
        }
    }

    private fun observeConnection() {
        scope.launch {
            container.bleConnectionManager.state.collect { state ->
                val activeMac = container.collectionSession.status.value.mac
                val disconnected = when (state) {
                    is BleConnectionState.Idle,
                    is BleConnectionState.Disconnected,
                    is BleConnectionState.KeyMissing,
                    -> true

                    is BleConnectionState.Connected ->
                        !state.mac.equals(activeMac, ignoreCase = true)

                    is BleConnectionState.Connecting ->
                        !state.mac.equals(activeMac, ignoreCase = true)
                }
                if (disconnected) stopCollection()
            }
        }
    }

    private fun stopCollection() {
        container.collectionSession.stop()
        NotificationManagerCompat.from(this).cancel(CollectionNotificationFactory.NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        container.collectionSession.stop()
        scope.cancel()
        foregroundStarted = false
        super.onDestroy()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

    companion object {
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
    }
}
