package com.platojobs.pkotlinapp.ui.common

import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.UNKNOWN_RSSI
import com.platojobs.pkotlinapp.domain.model.localRssi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiLevelTest {

    @Test
    fun `null rssi is unknown`() {
        assertEquals(RssiLevel.Tier.UNKNOWN, RssiLevel.tier(null))
        assertEquals(0, RssiLevel.level(null))
        assertEquals(0, RssiLevel.bars(null, 4))
    }

    @Test
    fun `unknown sentinel is not treated as very weak`() {
        assertEquals(RssiLevel.Tier.UNKNOWN, RssiLevel.tier(UNKNOWN_RSSI))
        assertEquals(0, RssiLevel.level(UNKNOWN_RSSI))
        // 哨兵值和 null 必须同档，否则通知等通道打开详情会显示成「信号极弱」。
        assertEquals(RssiLevel.tier(null), RssiLevel.tier(UNKNOWN_RSSI))
        assertNotEquals(RssiLevel.Tier.VERY_WEAK, RssiLevel.tier(UNKNOWN_RSSI))
    }

    @Test
    fun `unknown and very weak have distinct descriptions`() {
        assertEquals(R.string.rssi_desc_unknown, RssiLevel.descriptionRes(null))
        assertEquals(R.string.rssi_desc_unknown, RssiLevel.descriptionRes(UNKNOWN_RSSI))
        assertEquals(R.string.rssi_desc_very_weak, RssiLevel.descriptionRes(-86))
        assertEquals(R.string.rssi_desc_very_weak, RssiLevel.descriptionRes(-127))
        assertEquals(R.string.rssi_desc_weak, RssiLevel.descriptionRes(-85))
    }

    @Test
    fun `tier boundaries are inclusive lower bounds`() {
        assertEquals(RssiLevel.Tier.STRONG, RssiLevel.tier(RssiLevel.STRONG_MIN))
        assertEquals(RssiLevel.Tier.GOOD, RssiLevel.tier(RssiLevel.STRONG_MIN - 1))
        assertEquals(RssiLevel.Tier.GOOD, RssiLevel.tier(RssiLevel.GOOD_MIN))
        assertEquals(RssiLevel.Tier.FAIR, RssiLevel.tier(RssiLevel.GOOD_MIN - 1))
        assertEquals(RssiLevel.Tier.FAIR, RssiLevel.tier(RssiLevel.FAIR_MIN))
        assertEquals(RssiLevel.Tier.WEAK, RssiLevel.tier(RssiLevel.FAIR_MIN - 1))
        assertEquals(RssiLevel.Tier.WEAK, RssiLevel.tier(RssiLevel.WEAK_MIN))
        assertEquals(RssiLevel.Tier.VERY_WEAK, RssiLevel.tier(RssiLevel.WEAK_MIN - 1))
    }

    @Test
    fun `documented thresholds are the only source`() {
        assertEquals(-55, RssiLevel.STRONG_MIN)
        assertEquals(-66, RssiLevel.GOOD_MIN)
        assertEquals(-75, RssiLevel.FAIR_MIN)
        assertEquals(-85, RssiLevel.WEAK_MIN)
        assertEquals(4, RssiLevel.MAX_LEVEL)
    }

    @Test
    fun `level maps rssi thresholds`() {
        assertEquals(4, RssiLevel.level(-40))
        assertEquals(4, RssiLevel.level(-55))
        assertEquals(3, RssiLevel.level(-56))
        assertEquals(3, RssiLevel.level(-66))
        assertEquals(2, RssiLevel.level(-67))
        assertEquals(2, RssiLevel.level(-75))
        assertEquals(1, RssiLevel.level(-76))
        assertEquals(1, RssiLevel.level(-85))
        assertEquals(0, RssiLevel.level(-86))
    }

    /** 每一个 dBm 值都恰好落在一档，且随信号增强单调不减。 */
    @Test
    fun `every rssi maps to exactly one tier`() {
        var previous = 0
        for (rssi in -150..20) {
            val tier = RssiLevel.tier(rssi)
            assertNotEquals(RssiLevel.Tier.UNKNOWN, tier)
            assertTrue("level out of range at $rssi", tier.level in 0..RssiLevel.MAX_LEVEL)
            assertTrue("level not monotonic at $rssi", tier.level >= previous)
            previous = tier.level
        }
    }

    /** 边界两侧的档位必须相邻且相差 1 格，不存在两档都不覆盖的缝隙。 */
    @Test
    fun `tier boundaries have no gaps`() {
        val boundaries = listOf(
            RssiLevel.STRONG_MIN,
            RssiLevel.GOOD_MIN,
            RssiLevel.FAIR_MIN,
            RssiLevel.WEAK_MIN,
        )
        boundaries.forEach { boundary ->
            val above = RssiLevel.level(boundary)
            val below = RssiLevel.level(boundary - 1)
            assertEquals("boundary $boundary", 1, above - below)
        }
    }

    @Test
    fun `bars scale with level and bar count`() {
        assertEquals(4, RssiLevel.bars(-50, 4))
        assertEquals(3, RssiLevel.bars(-60, 4))
        assertEquals(2, RssiLevel.bars(-70, 4))
        assertEquals(1, RssiLevel.bars(-80, 4))
        assertEquals(0, RssiLevel.bars(-90, 4))

        assertEquals(5, RssiLevel.bars(-50, 5))
        assertEquals(4, RssiLevel.bars(-60, 5))
        assertEquals(3, RssiLevel.bars(-70, 5))
        assertEquals(2, RssiLevel.bars(-80, 5))
        assertEquals(0, RssiLevel.bars(-90, 5))

        assertEquals(2, RssiLevel.bars(-50, 2))
        assertEquals(1, RssiLevel.bars(-70, 2))
        assertEquals(0, RssiLevel.bars(-90, 2))
    }

    @Test
    fun `bar count is clamped`() {
        assertEquals(0, RssiLevel.bars(-40, -1))
        assertEquals(0, RssiLevel.bars(-40, 0))
        assertEquals(4, RssiLevel.bars(-40, 4))
    }

    /** 云端同步来的设备 lastRssi 占位 0，展示前必须转成未知，不能当成 0 dBm 的满格信号。 */
    @Test
    fun `device placeholder rssi is unknown instead of strong`() {
        val synced = Device(mac = "AA:BB:CC:00:00:01", lastRssi = 0, lastSeenTimestamp = 0L)
        assertEquals(null, synced.localRssi)
        assertEquals(RssiLevel.Tier.UNKNOWN, RssiLevel.tier(synced.localRssi))
        assertEquals(0, RssiLevel.bars(synced.localRssi, 4))

        val scanned = synced.copy(lastRssi = -72)
        assertEquals(-72, scanned.localRssi)
        assertEquals(RssiLevel.Tier.FAIR, RssiLevel.tier(scanned.localRssi))
        assertEquals(2, RssiLevel.bars(scanned.localRssi, 4))
    }
}
