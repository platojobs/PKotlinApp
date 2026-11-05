package com.platojobs.pkotlinapp.data.collection

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.ui.common.NotificationPermission

/** 启动结果：是否真的启动，以及通知权限是否已授予（未授予时仍可采集，只是通知不可见）。 */
data class CollectionStartResult(
    val started: Boolean,
    val notificationsGranted: Boolean,
)

/** UI 侧启停入口；所有启动都来自前台界面，符合 Android 12+ 前台服务限制。 */
object CollectionLauncher {

    fun start(context: Context, mac: String, deviceName: String): CollectionStartResult {
        val granted = NotificationPermission.isGranted(context)
        val started = runCatching {
            val intent = Intent(context, ReadingCollectionService::class.java).apply {
                putExtra(ReadingCollectionService.EXTRA_MAC, mac)
                putExtra(ReadingCollectionService.EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        }.getOrDefault(false)
        return CollectionStartResult(started = started, notificationsGranted = granted)
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, ReadingCollectionService::class.java)) }
    }
}
