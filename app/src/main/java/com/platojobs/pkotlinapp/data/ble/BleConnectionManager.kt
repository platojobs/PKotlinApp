package com.platojobs.pkotlinapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单设备 GATT 连接管理。刻意不实现自动重连：
 * 收到 Android 16 的 ACTION_KEY_MISSING 后会写入 blockedMacs，
 * 必须由用户点击“已重新配对，重试连接”后才会解除。
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val blockedMacs = mutableSetOf<String>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleConnectionState.Connected(mac)
                    runCatching { gatt.discoverServices() }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { gatt.close() }
                    if (this@BleConnectionManager.gatt === gatt) {
                        this@BleConnectionManager.gatt = null
                    }
                    _rssi.value = null
                    _state.value = if (blockedMacs.contains(mac)) {
                        BleConnectionState.KeyMissing(mac)
                    } else {
                        BleConnectionState.Disconnected(mac)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }

    fun connect(mac: String): Boolean {
        if (blockedMacs.contains(mac)) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false

        closeCurrent()
        _rssi.value = null
        _state.value = BleConnectionState.Connecting(mac)

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: run {
            _state.value = BleConnectionState.Disconnected(mac)
            return false
        }
        gatt = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            _state.value = BleConnectionState.Disconnected(mac)
            return false
        }
        return true
    }

    fun disconnect(mac: String? = null) {
        val current = gatt?.device?.address ?: mac
        closeCurrent()
        _rssi.value = null
        _state.value = when {
            current == null -> BleConnectionState.Idle
            blockedMacs.contains(current) -> BleConnectionState.KeyMissing(current)
            else -> BleConnectionState.Disconnected(current)
        }
    }

    /** Android 16 绑定信息丢失：立即断开并禁止自动/手动重连，直到用户重新配对后确认。 */
    fun markKeyMissing(mac: String) {
        blockedMacs += mac
        _state.value = BleConnectionState.KeyMissing(mac)
        gatt?.let { runCatching { it.disconnect() } }
        gatt?.let { runCatching { it.close() } }
        gatt = null
        _rssi.value = null
    }

    /** 用户在系统设置中重新配对后，允许再次连接。 */
    fun retryAfterRepair(mac: String): Boolean {
        blockedMacs -= mac
        return connect(mac)
    }

    fun isReconnectBlocked(mac: String): Boolean = blockedMacs.contains(mac)

    fun readRssi() {
        runCatching { gatt?.readRemoteRssi() }
    }

    private fun closeCurrent() {
        gatt?.let { current ->
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
        gatt = null
    }
}
