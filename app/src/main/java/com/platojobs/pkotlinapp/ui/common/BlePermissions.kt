package com.platojobs.pkotlinapp.ui.common

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.R

/**
 * Android 12+ 使用 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；
 * Android 11 及以下扫描依赖 ACCESS_FINE_LOCATION（蓝牙权限为安装时普通权限）。
 */
object BlePermissions {

    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    fun hasLocation(context: Context): Boolean =
        granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun requiresLocation(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    @SuppressLint("MissingPermission")
    fun bluetoothEnabled(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
