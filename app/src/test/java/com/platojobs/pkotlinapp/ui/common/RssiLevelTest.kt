package com.platojobs.pkotlinapp.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RssiLevelTest {

    @Test
    fun `null rssi has no level and no bars`() {
        assertEquals(0, RssiLevel.level(null))
        assertEquals(0, RssiLevel.bars(null, 4))
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
    }

    @Test
    fun `bar count is clamped`() {
        assertEquals(0, RssiLevel.bars(-40, -1))
        assertEquals(0, RssiLevel.bars(-40, 0))
        assertEquals(4, RssiLevel.bars(-40, 4))
    }
}
