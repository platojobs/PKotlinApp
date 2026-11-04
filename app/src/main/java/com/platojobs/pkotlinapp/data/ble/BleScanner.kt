package com.platojobs.pkotlinapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 使用 BluetoothLeScanner 的实时扫描流。
 * Flow 被取消时（例如 Fragment onStop）会停止扫描。
 */
class BleScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BleException(BleError.UNSUPPORTED)
        if (!adapter.isEnabled) throw BleException(BleError.BLUETOOTH_DISABLED)
        val scanner = adapter.bluetoothLeScanner
            ?: throw BleException(BleError.UNSUPPORTED)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toScannedDevice())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toScannedDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException(BleError.SCAN_FAILED, errorCode))
            }
        }

        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            throw BleException(BleError.MISSING_PERMISSION)
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toScannedDevice(): ScannedDevice = ScannedDevice(
        name = scanRecord?.deviceName?.takeIf { it.isNotBlank() },
        mac = device.address,
        rssi = rssi,
        timestamp = System.currentTimeMillis(),
    )
}
