package com.platojobs.pkotlinapp.data.scene

import android.content.Context
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
}
