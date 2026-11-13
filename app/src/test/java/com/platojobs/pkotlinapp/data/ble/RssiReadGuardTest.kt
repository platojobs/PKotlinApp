package com.platojobs.pkotlinapp.data.ble

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁住 RSSI 在途互斥的释放路径：失败回调、超时、断开都必须复位，
 * 否则读数会冻结到重连；成功回调返回非负值按失败处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RssiReadGuardTest {

    private val scheduler = FakeScheduler()
    private var timeouts = 0
    private val guard = RssiReadGuard(
        schedule = scheduler::schedule,
        onTimeout = { timeouts++ },
    )

    @Test
    fun `duplicate request is ignored while one read is in flight`() {
        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())
        assertTrue(guard.isInFlight)
        assertEquals(RssiReadGuard.DEFAULT_TIMEOUT_MS, scheduler.lastDelay)
        // 默认超时落在 5~10 秒，既能兜底又不会早于一次慢回调。
        assertTrue(scheduler.lastDelay in 5_000L..10_000L)
    }

    @Test
    fun `failure callback releases the slot`() {
        guard.tryStart()
        guard.finish()
        assertFalse(guard.isInFlight)
        assertEquals(0, timeouts)
        assertTrue(guard.tryStart())
    }

    @Test
    fun `timeout releases the slot and reports stale`() {
        guard.tryStart()
        scheduler.fire()
        assertFalse(guard.isInFlight)
        assertEquals(1, timeouts)
        // 超时后下一次轮询能立即占用名额，读数不会冻结。
        assertTrue(guard.tryStart())
    }

    @Test
    fun `disconnect reset releases the slot without flagging stale`() {
        guard.tryStart()
        guard.reset()
        assertFalse(guard.isInFlight)
        assertEquals(0, timeouts)
        assertTrue(guard.tryStart())
    }

    @Test
    fun `non negative rssi counts as a failed read even with success status`() {
        assertTrue(isRssiReadResultUsable(BluetoothGatt.GATT_SUCCESS, -72))
        assertTrue(isRssiReadResultUsable(BluetoothGatt.GATT_SUCCESS, -55))
        assertFalse(isRssiReadResultUsable(BluetoothGatt.GATT_SUCCESS, 0))
        assertFalse(isRssiReadResultUsable(BluetoothGatt.GATT_SUCCESS, 127))
        assertFalse(isRssiReadResultUsable(BluetoothGatt.GATT_FAILURE, -72))
    }

    private class FakeScheduler {
        var lastDelay = -1L
        private var pending: (() -> Unit)? = null

        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            lastDelay = delayMs
            pending = action
            return { pending = null }
        }

        fun fire() {
            pending?.invoke()
        }
    }
}
