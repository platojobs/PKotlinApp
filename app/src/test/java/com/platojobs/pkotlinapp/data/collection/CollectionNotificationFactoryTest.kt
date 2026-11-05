package com.platojobs.pkotlinapp.data.collection

import android.app.Notification
import android.app.NotificationManager
import com.platojobs.pkotlinapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollectionNotificationFactoryTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `通知包含设备名与已采集条数`() {
        val notification = CollectionNotificationFactory.build(
            context,
            CollectionStatus(active = true, mac = "AA:BB", deviceName = "心率带", count = 42),
        )

        val extras = notification.extras
        assertEquals(
            context.getString(R.string.collection_notification_title),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.collection_notification_text, "心率带", 42),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(CollectionNotificationFactory.CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `通知常驻且带停止动作`() {
        val notification = CollectionNotificationFactory.build(
            context,
            CollectionStatus(active = true, mac = "AA:BB", deviceName = "", count = 0),
        )

        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(R.string.collection_notification_stop),
            notification.actions[0].title.toString(),
        )
        assertNotNull(notification.contentIntent)
        // 设备名为空时回退 MAC，通知正文不应出现空白设备名。
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertTrue(text?.contains("AA:BB") == true)
    }

    @Test
    fun `创建单位为数据采集的渠道`() {
        CollectionNotificationFactory.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CollectionNotificationFactory.CHANNEL_ID)

        assertNotNull(channel)
        assertEquals(context.getString(R.string.collection_channel_name), channel?.name)
    }
}
