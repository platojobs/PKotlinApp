package com.platojobs.pkotlinapp.data.notification

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.rule.AlertStateStore
import com.platojobs.pkotlinapp.domain.rule.AlertThrottle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertNotifierTest {

    private class MemoryStore : AlertStateStore {
        val last = mutableMapOf<String, Long>()
        val pending = mutableMapOf<String, Boolean>()

        override fun lastNotifiedAt(key: String): Long = last[key] ?: 0L
        override fun putLastNotifiedAt(key: String, timestamp: Long) {
            last[key] = timestamp
        }

        override fun isPendingRecovery(key: String): Boolean = pending[key] ?: false
        override fun putPendingRecovery(key: String, pending: Boolean) {
            this.pending[key] = pending
        }
    }

    private class FakePoster : AlertPoster {
        val posted = mutableListOf<AlertNotificationContent>()
        override fun post(content: AlertNotificationContent) {
            posted.add(content)
        }
    }

    private class FakeContentBuilder : AlertContentBuilder {
        override fun triggered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int?,
            timestamp: Long,
        ) = AlertNotificationContent(
            id = AlertNotificationIds.forRule(rule.id, mac),
            channelId = AlertNotificationFactory.CHANNEL_ID,
            title = "设备告警",
            text = "$displayName,$rssi,${rule.threshold},$timestamp",
            mac = mac,
            deviceName = displayName,
            rssi = rssi,
            triggeredAt = timestamp,
        )

        override fun recovered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int,
        ) = AlertNotificationContent(
            id = AlertNotificationIds.forRule(rule.id, mac),
            channelId = AlertNotificationFactory.CHANNEL_ID,
            title = "告警已恢复",
            text = "$displayName,$rssi",
            mac = mac,
            deviceName = displayName,
            rssi = rssi,
            triggeredAt = 0L,
        )
    }

    private var now = 1_000L
    private var enabled = true
    private var canNotify = true
    private val store = MemoryStore()
    private val poster = FakePoster()
    private val rule = DeviceRule(
        id = 7,
        type = RuleType.LOW_RSSI,
        threshold = -70,
        enabled = true,
        createdAt = 0,
        lastTriggeredTimestamp = 0,
    )
    private val notifier = AlertNotifier(
        throttle = AlertThrottle(store, { 5 * 60_000L }, { now }),
        isEnabled = { enabled },
        canNotify = { canNotify },
        contentBuilder = FakeContentBuilder(),
        poster = poster,
    )

    @Test
    fun `开关关闭时不投递通知`() = runBlocking {
        enabled = false
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        assertTrue(poster.posted.isEmpty())
        // 关闭期间不记节流，重新开启后仍能提醒。
        enabled = true
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        assertEquals(1, poster.posted.size)
    }

    @Test
    fun `权限缺失时不投递通知`() = runBlocking {
        canNotify = false
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `窗口内多次触发合并为一条通知`() = runBlocking {
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        now += 30_000L
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -86, now)
        assertEquals(1, poster.posted.size)
        assertTrue(poster.posted.single().text.contains("-85"))
    }

    @Test
    fun `恢复通知只发一次`() = runBlocking {
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        notifier.onRecovered(rule, "AA:BB", "客厅温度计", -60)
        notifier.onRecovered(rule, "AA:BB", "客厅温度计", -55)
        assertEquals(2, poster.posted.size)
        assertEquals("告警已恢复", poster.posted[1].title)
    }

    @Test
    fun `未告警过不发恢复通知`() = runBlocking {
        notifier.onRecovered(rule, "AA:BB", "客厅温度计", -60)
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `关闭开关时恢复状态被消费且不补发`() = runBlocking {
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        enabled = false
        notifier.onRecovered(rule, "AA:BB", "客厅温度计", -60)
        assertEquals(1, poster.posted.size)
        enabled = true
        notifier.onRecovered(rule, "AA:BB", "客厅温度计", -55)
        assertEquals(1, poster.posted.size)
        assertFalse(store.isPendingRecovery("7:AA:BB"))
    }

    @Test
    fun `通知内容不含敏感信息标记`() = runBlocking {
        notifier.onTriggered(rule, "AA:BB", "客厅温度计", -85, now)
        val content = poster.posted.single()
        assertFalse(content.text.contains("token", ignoreCase = true))
        assertFalse(content.text.contains("account", ignoreCase = true))
    }
}
