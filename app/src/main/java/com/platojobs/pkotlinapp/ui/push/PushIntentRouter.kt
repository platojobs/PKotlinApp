package com.platojobs.pkotlinapp.ui.push

import android.content.Context
import android.content.Intent
import com.platojobs.pkotlinapp.data.push.PushDestination
import com.platojobs.pkotlinapp.data.push.PushMessage
import com.platojobs.pkotlinapp.data.push.PushMessageHandler
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity

/**
 * 通知点击落点：由未来的 FCM Service 在 `onMessageClicked` 时调用。
 * 当前没有真实推送，但路由逻辑已可被单测与后续实现复用。
 */
object PushIntentRouter {

    fun createIntent(context: Context, message: PushMessage): Intent? =
        when (val destination = PushMessageHandler.destination(message)) {
            is PushDestination.DeviceDetail -> DeviceDetailActivity.createIntent(
                context = context,
                mac = destination.mac,
                name = destination.name.orEmpty(),
                rssi = Int.MIN_VALUE,
            )
            PushDestination.Scenes -> Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SCENES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PushDestination.Unknown -> null
        }
}
