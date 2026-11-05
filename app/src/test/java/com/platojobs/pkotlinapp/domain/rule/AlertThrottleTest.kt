package com.platojobs.pkotlinapp.domain.rule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertThrottleTest {

    private class FakeStore : AlertStateStore {
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

    private var now = 1_000L
    private var window = 5 * 60_000L
    private val store = FakeStore()
    private val throttle = AlertThrottle(store, { window }, { now })

    @Test
    fun `窗口内同一设备同一规则只允许一次`() {
        assertTrue(throttle.allowTrigger("1:AA"))
        now += 60_000L
        assertFalse(throttle.allowTrigger("1:AA"))
        now += 60_000L
        assertFalse(throttle.allowTrigger("1:AA"))
    }

    @Test
    fun `窗口结束后允许再次通知`() {
        assertTrue(throttle.allowTrigger("1:AA"))
        now += 5 * 60_000L
        assertTrue(throttle.allowTrigger("1:AA"))
    }

    @Test
    fun `不同设备或不同规则互不影响`() {
        assertTrue(throttle.allowTrigger("1:AA"))
        assertTrue(throttle.allowTrigger("1:BB"))
        assertTrue(throttle.allowTrigger("2:AA"))
    }

    @Test
    fun `可配置 1 分钟与 30 分钟窗口`() {
        window = 60_000L
        assertTrue(throttle.allowTrigger("1:AA"))
        now += 61_000L
        assertTrue(throttle.allowTrigger("1:AA"))

        window = 30 * 60_000L
        now += 10 * 60_000L
        assertFalse(throttle.allowTrigger("1:AA"))
    }

    @Test
    fun `恢复只在真正告警后发一次`() {
        assertTrue(throttle.allowTrigger("1:AA"))
        assertTrue(throttle.allowRecovery("1:AA"))
        assertFalse(throttle.allowRecovery("1:AA"))
    }

    @Test
    fun `没有告警过不发恢复`() {
        assertFalse(throttle.allowRecovery("1:AA"))
    }

    @Test
    fun `恢复后立刻再次触发可通知`() {
        assertTrue(throttle.allowTrigger("1:AA"))
        assertTrue(throttle.allowRecovery("1:AA"))
        assertTrue(throttle.allowTrigger("1:AA"))
        assertFalse(throttle.allowTrigger("1:AA"))
    }
}
