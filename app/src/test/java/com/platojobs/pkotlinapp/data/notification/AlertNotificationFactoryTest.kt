package com.platojobs.pkotlinapp.data.notification

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 用 NotificationCompat 构建真实通知对象并断言字段，
 * 避免只测字符串拼接而漏掉渠道、标题、点击意图等关键配置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertNotificationFactoryTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `构建告警通知包含标题正文渠道与点击意图`() {
        val content = AlertNotificationContent(
            id = 9,
            channelId = AlertNotificationFactory.CHANNEL_ID,
            title = "设备告警",
            text = "客厅温度计 · 信号强度 -85 dBm（阈值 -70 dBm）· 10:32",
            mac = "AA:BB:CC:DD:EE:FF",
            deviceName = "客厅温度计",
            rssi = -85,
            triggeredAt = 1_000L,
        )

        val notification = AlertNotificationFactory.build(context, content).build()

        assertEquals(
            "设备告警",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            content.text,
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString(),
        )
        assertEquals(AlertNotificationFactory.CHANNEL_ID, notification.channelId)
        assertNotNull(notification.contentIntent)
        assertTrue(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `设备告警渠道已创建且名称正确`() {
        AlertNotificationFactory.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AlertNotificationFactory.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("设备告警", channel.name.toString())
    }

    @Test
    fun `文案包含设备指标当前值阈值与时间且不含敏感字段`() {
        val rule = DeviceRule(
            id = 1,
            type = RuleType.LOW_RSSI,
            threshold = -70,
            enabled = true,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
        )
        val content = AndroidAlertContentBuilder(context).triggered(
            rule = rule,
            mac = "AA:BB",
            displayName = "客厅温度计",
            rssi = -85,
            timestamp = 0L,
        )
        assertTrue(content.text.contains("客厅温度计"))
        assertTrue(content.text.contains("信号强度"))
        assertTrue(content.text.contains("-85"))
        assertTrue(content.text.contains("-70"))
        assertTrue(content.text.contains(":"))
        assertTrue(!content.text.contains("token", ignoreCase = true))
        assertTrue(!content.text.contains("账号"))
    }
}
