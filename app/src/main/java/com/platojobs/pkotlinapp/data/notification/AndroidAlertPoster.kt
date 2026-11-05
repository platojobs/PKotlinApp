package com.platojobs.pkotlinapp.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

interface AlertPoster {
    fun post(content: AlertNotificationContent)
}

/** 真正投递通知；权限被拒或系统异常时静默降级，不影响其它功能。 */
class AndroidAlertPoster(private val context: Context) : AlertPoster {

    override fun post(content: AlertNotificationContent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val notification = AlertNotificationFactory.build(context, content).build()
            NotificationManagerCompat.from(context).notify(content.id, notification)
        }
    }
}
