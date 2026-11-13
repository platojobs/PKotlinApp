package com.platojobs.pkotlinapp.ui.detail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁住详情页 RSSI 轮询的开关：页面不可见立即停表，回到前台再起。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssiPollerTest {

    @Test
    fun `hiding the page stops polling and resuming restarts it`() = runTest {
        var reads = 0
        val poller = RssiPoller(backgroundScope, INTERVAL_MS) { reads++ }

        poller.sync(true)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(poller.isRunning)

        poller.sync(false)
        assertFalse(poller.isRunning)
        advanceTimeBy(10 * INTERVAL_MS)
        runCurrent()
        // 停表后不再产生任何读取，已读到的值由上层保留。
        assertEquals(1, reads)

        poller.sync(true)
        runCurrent()
        assertEquals(2, reads)
        advanceTimeBy(INTERVAL_MS)
        runCurrent()
        assertEquals(3, reads)

        poller.sync(false)
    }

    @Test
    fun `repeated sync while running does not start a second loop`() = runTest {
        var reads = 0
        val poller = RssiPoller(backgroundScope, INTERVAL_MS) { reads++ }

        poller.sync(true)
        poller.sync(true)
        runCurrent()
        assertEquals(1, reads)

        advanceTimeBy(INTERVAL_MS)
        runCurrent()
        assertEquals(2, reads)

        poller.sync(false)
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}
